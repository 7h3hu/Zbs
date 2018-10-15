package com.zbsplatform.matcher.market

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestProbe}
import com.zbsplatform.account.PrivateKeyAccount
import com.zbsplatform.matcher.MatcherTestData
import com.zbsplatform.matcher.api.{OperationTimedOut, OrderAccepted, OrderCanceled}
import com.zbsplatform.matcher.fixtures.RestartableActor
import com.zbsplatform.matcher.fixtures.RestartableActor.RestartActor
import com.zbsplatform.matcher.market.OrderBookActor._
import com.zbsplatform.matcher.market.OrderHistoryActor.{ValidateOrder, ValidateOrderResult}
import com.zbsplatform.matcher.model.{BuyLimitOrder, LimitOrder, OrderBook, SellLimitOrder}
import com.zbsplatform.settings.{Constants, FunctionalitySettings, TestFunctionalitySettings, WalletSettings}
import com.zbsplatform.state.{Blockchain, ByteStr, Diff, LeaseBalance, Portfolio}
import com.zbsplatform.transaction._
import com.zbsplatform.transaction.assets.IssueTransactionV1
import com.zbsplatform.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import com.zbsplatform.utils.NTP
import com.zbsplatform.utx.UtxPool
import com.zbsplatform.wallet.Wallet
import io.netty.channel.group.ChannelGroup
import org.scalamock.scalatest.PathMockFactory

import scala.concurrent.duration._
import scala.util.Random

class OrderBookActorSpecification extends MatcherSpec("OrderBookActor") with ImplicitSender with MatcherTestData with PathMockFactory {

  val defaultPair            = AssetPair(Some(ByteStr("BTC".getBytes)), Some(ByteStr("ZBS".getBytes)))
  val blockchain: Blockchain = stub[Blockchain]
  val hugeAmount             = Long.MaxValue / 2
  (blockchain.portfolio _)
    .when(*)
    .returns(
      Portfolio(hugeAmount,
                LeaseBalance.empty,
                Map(
                  ByteStr("BTC".getBytes) -> hugeAmount,
                  ByteStr("ZBS".getBytes) -> hugeAmount
                )))
  val issueTransaction: IssueTransactionV1 = IssueTransactionV1
    .selfSigned(PrivateKeyAccount("123".getBytes), "MinerReward".getBytes, Array.empty, 10000000000L, 8.toByte, true, 100000L, 10000L)
    .right
    .get

  (blockchain.transactionInfo _).when(*).returns(Some((1, issueTransaction)))

  val settings = matcherSettings.copy(account = MatcherAccount.address)

  val wallet = Wallet(WalletSettings(None, "matcher", Some(WalletSeed)))
  wallet.generateNewAccount()

  val orderHistoryRef = TestActorRef(new Actor {
    def receive: Receive = {
      case ValidateOrder(o, _) => sender() ! ValidateOrderResult(o.id(), Right(o))
      case _                   =>
    }
  })

  val obc                                              = new ConcurrentHashMap[AssetPair, OrderBook]()
  def update(ap: AssetPair)(snapshot: OrderBook): Unit = obc.put(ap, snapshot)

  private def getOrders(actor: ActorRef) = {
    actor ! GetOrdersRequest
    receiveN(1).head.asInstanceOf[GetOrdersResponse].orders
  }

  private def obcTest(f: (AssetPair, ActorRef) => Unit): Unit = {
    obc.clear()
    val b = ByteStr(new Array[Byte](32))
    Random.nextBytes(b.arr)

    val pair                  = AssetPair(Some(b), None)
    val blockchain            = stub[Blockchain]
    val functionalitySettings = TestFunctionalitySettings.Stub

    val utx = stub[UtxPool]
    (utx.putIfNew _).when(*).onCall((_: Transaction) => Right((true, Diff.empty)))
    val allChannels = stub[ChannelGroup]
    val actor = system.actorOf(
      Props(
        new OrderBookActor(pair, update(pair), orderHistoryRef, blockchain, wallet, utx, allChannels, settings, functionalitySettings)
        with RestartableActor))

    f(pair, actor)
  }

  "OrderBookActor" should {

    "place buy orders" in obcTest { (pair, actor) =>
      val ord1 = buy(pair, 34118, 1583290045643L)
      val ord2 = buy(pair, 34120, 170484969L)
      val ord3 = buy(pair, 34000, 44521418496L)

      actor ! ord1
      expectMsg(OrderAccepted(ord1))
      actor ! ord2
      expectMsg(OrderAccepted(ord2))
      actor ! ord3
      expectMsg(OrderAccepted(ord3))

      actor ! GetOrdersRequest
      expectMsg(GetOrdersResponse(Seq(ord2, ord1, ord3).map(LimitOrder(_))))
    }

    "place sell orders" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 34110, 1583290045643L)
      val ord2 = sell(pair, 34220, 170484969L)
      val ord3 = sell(pair, 34000, 44521418496L)

      actor ! ord1
      expectMsg(OrderAccepted(ord1))
      actor ! ord2
      expectMsg(OrderAccepted(ord2))
      actor ! ord3
      expectMsg(OrderAccepted(ord3))

      actor ! GetOrdersRequest
      expectMsg(GetOrdersResponse(Seq(ord3, ord1, ord2).map(LimitOrder(_))))
    }

    "sell market" in obcTest { (pair, actor) =>
      val ord1 = buy(pair, 100, 10 * Order.PriceConstant)
      val ord2 = buy(pair, 105, 10 * Order.PriceConstant)

      actor ! ord1
      actor ! ord2
      receiveN(2)
      actor ! GetOrdersRequest
      expectMsg(
        GetOrdersResponse(
          Seq(BuyLimitOrder(ord2.price, ord2.amount, ord2.matcherFee, ord2), BuyLimitOrder(ord1.price, ord1.amount, ord1.matcherFee, ord1))))

      val ord3 = sell(pair, 100, 10 * Order.PriceConstant)
      actor ! ord3
      expectMsg(OrderAccepted(ord3))

      actor ! GetOrdersRequest
      expectMsg(GetOrdersResponse(Seq(BuyLimitOrder(ord1.price, ord1.amount, ord1.matcherFee, ord1))))
    }

    "place buy and sell order to the order book and preserve it after restart" in obcTest { (pair, actor) =>
      val ord1 = buy(pair, 100, 10 * Order.PriceConstant)
      val ord2 = sell(pair, 150, 15 * Order.PriceConstant)

      actor ! ord1
      actor ! ord2
      receiveN(2)

      actor ! RestartActor
      actor ! GetOrdersRequest

      expectMsg(GetOrdersResponse(Seq(ord2, ord1).map(LimitOrder(_))))
    }

    "execute partial market orders and preserve remaining after restart" in obcTest { (pair, actor) =>
      val ord1 = buy(pair, 100, 10 * Order.PriceConstant)
      val ord2 = sell(pair, 100, 15 * Order.PriceConstant)

      actor ! ord1
      expectMsgType[OrderAccepted]
      actor ! ord2
      expectMsgType[OrderAccepted]

      actor ! RestartActor
      actor ! GetOrdersRequest

      expectMsg(
        GetOrdersResponse(
          Seq(
            SellLimitOrder(
              ord2.price,
              ord2.amount - ord1.amount,
              ord2.matcherFee - LimitOrder.getPartialFee(ord2.matcherFee, ord2.amount, ord1.amount),
              ord2
            ))))
    }

    "execute one order fully and other partially and restore after restart" in obcTest { (pair, actor) =>
      val ord1 = buy(pair, 100, 10 * Order.PriceConstant)
      val ord2 = buy(pair, 100, 5 * Order.PriceConstant)
      val ord3 = sell(pair, 100, 12 * Order.PriceConstant)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      receiveN(3)

      actor ! RestartActor

      actor ! GetBidOrdersRequest
      val restAmount = ord1.amount + ord2.amount - ord3.amount
      expectMsg(
        GetOrdersResponse(
          Seq(
            BuyLimitOrder(
              ord2.price,
              restAmount,
              ord2.matcherFee - LimitOrder.getPartialFee(ord2.matcherFee, ord2.amount, ord2.amount - restAmount),
              ord2
            ))))

      actor ! GetAskOrdersRequest
      expectMsg(GetOrdersResponse(Seq.empty))
    }

    "match multiple best orders at once and restore after restart" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 100, 10 * Order.PriceConstant)
      val ord2 = sell(pair, 100, 5 * Order.PriceConstant)
      val ord3 = sell(pair, 90, 5 * Order.PriceConstant)
      val ord4 = buy(pair, 100, 19 * Order.PriceConstant)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      actor ! ord4
      receiveN(4)

      actor ! RestartActor

      actor ! GetBidOrdersRequest
      expectMsg(GetOrdersResponse(Seq.empty))

      actor ! GetAskOrdersRequest
      val restAmount = ord1.amount + ord2.amount + ord3.amount - ord4.amount
      expectMsg(
        GetOrdersResponse(
          Seq(
            SellLimitOrder(
              ord2.price,
              restAmount,
              ord2.matcherFee - LimitOrder.getPartialFee(ord2.matcherFee, ord2.amount, ord2.amount - restAmount),
              ord2
            ))))

    }

    "execute orders at different price levels" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 100, 10 * Order.PriceConstant)
      val ord2 = sell(pair, 110, 5 * Order.PriceConstant)
      val ord3 = sell(pair, 110, 10 * Order.PriceConstant)
      val ord4 = buy(pair, 115, 22 * Order.PriceConstant)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      actor ! ord4
      receiveN(4)

      actor ! GetBidOrdersRequest
      expectMsg(GetOrdersResponse(Seq.empty))

      actor ! GetAskOrdersRequest
      val restAmount = ord1.amount + ord2.amount + ord3.amount - ord4.amount
      expectMsg(
        GetOrdersResponse(
          Seq(
            SellLimitOrder(ord3.price,
                           restAmount,
                           ord3.matcherFee - LimitOrder.getPartialFee(ord3.matcherFee, ord3.amount, ord3.amount - restAmount),
                           ord3))))
    }

    "place orders and restart without waiting for response" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 100, 10 * Order.PriceConstant)
      val ts   = System.currentTimeMillis()

      (1 to 100).foreach({ i =>
        actor ! ord1.copy(timestamp = ts + i)
      })

      within(10.seconds) {
        receiveN(100)
      }

      actor ! RestartActor

      within(10.seconds) {
        actor ! GetOrdersRequest
        expectMsgType[GetOrdersResponse].orders.map(_.order.id()) should have size 100
      }
    }

    "order matched with invalid order should keep matching with others, invalid is removed" in obcTest { (pair, _) =>
      val blockchain            = stub[Blockchain]
      val functionalitySettings = TestFunctionalitySettings.Stub

      val ord1       = buy(pair, 100, 20 * Order.PriceConstant)
      val invalidOrd = buy(pair, 5000, 1000 * Order.PriceConstant)
      val ord2       = sell(pair, 100, 10 * Order.PriceConstant)

      val pool = stub[UtxPool]
      (pool.putIfNew _).when(*).onCall { tx: Transaction =>
        tx match {
          case om: ExchangeTransaction if om.buyOrder == invalidOrd => Left(ValidationError.GenericError("test"))
          case _: Transaction                                       => Right((true, Diff.empty))
        }
      }
      val allChannels = stub[ChannelGroup]
      val actor = system.actorOf(
        Props(new OrderBookActor(pair, update(pair), orderHistoryRef, blockchain, wallet, pool, allChannels, settings, functionalitySettings)
        with RestartableActor))

      actor ! ord1
      expectMsg(OrderAccepted(ord1))
      actor ! invalidOrd
      expectMsg(OrderAccepted(invalidOrd))
      actor ! ord2
      expectMsg(OrderAccepted(ord2))

      actor ! RestartActor

      actor ! GetBidOrdersRequest
      val restAmount = ord1.amount - ord2.amount
      expectMsg(
        GetOrdersResponse(
          Seq(
            BuyLimitOrder(
              ord1.price,
              restAmount,
              ord1.matcherFee - LimitOrder.getPartialFee(ord1.matcherFee, ord1.amount, restAmount),
              ord1
            ))))

      actor ! GetAskOrdersRequest
      expectMsg(GetOrdersResponse(Seq.empty))

    }

    "partially execute order with small remaining part" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 0.00041, 200000000)
      val ord2 = sell(pair, 0.0004, 100000000)
      val ord3 = buy(pair, 0.00045, 100000001)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      receiveN(3)

      actor ! GetAskOrdersRequest
      expectMsg(GetOrdersResponse(Seq(SellLimitOrder(ord1.price, ord1.amount, ord1.matcherFee, ord1))))

    }

    "partially execute order with zero fee remaining part" in obcTest { (pair, actor) =>
      val ord1 = sell(pair, 0.0006999, 1500 * Constants.UnitsInZbs)
      val ord2 = sell(pair, 0.00067634, 3075248828L)
      val ord3 = buy(pair, 0.00073697, 3075363900L)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      receiveN(3)

      actor ! GetAskOrdersRequest
      val corrected1 = Order.correctAmount(ord2.amount, ord2.price)
      val leftovers1 = ord3.amount - corrected1
      val corrected2 = Order.correctAmount(leftovers1, ord1.price)
      val restAmount = ord1.amount - corrected2
      // See OrderExecuted.submittedRemainingFee
      val restFee = ord1.matcherFee - LimitOrder.getPartialFee(ord1.matcherFee, ord1.amount, corrected2)
      expectMsg(GetOrdersResponse(Seq(SellLimitOrder(ord1.price, restAmount, restFee, ord1))))
    }

    "partially execute order with price > 1 and zero fee remaining part " in obcTest { (pair, actor) =>
      val pair = AssetPair(Some(ByteStr("BTC".getBytes)), Some(ByteStr("USD".getBytes)))
      val ord1 = sell(pair, 1850, (0.1 * Constants.UnitsInZbs).toLong)
      val ord2 = sell(pair, 1840, (0.01 * Constants.UnitsInZbs).toLong)
      val ord3 = buy(pair, 2000, (0.0100001 * Constants.UnitsInZbs).toLong)

      actor ! ord1
      actor ! ord2
      actor ! ord3
      receiveN(3)

      actor ! GetAskOrdersRequest
      val restAmount = ord1.amount - (ord3.amount - ord2.amount)
      val restFee    = ord1.matcherFee - LimitOrder.getPartialFee(ord1.matcherFee, ord1.amount, ord3.amount - ord2.amount)
      expectMsg(GetOrdersResponse(Seq(SellLimitOrder(ord1.price, restAmount, restFee, ord1))))
    }

    "buy small amount of pricey asset" in obcTest { (pair, actor) =>
      val p = AssetPair(Some(ByteStr("ZBS".getBytes)), Some(ByteStr("USD".getBytes)))
      val b = rawBuy(p, 280, 700000L)
      val s = rawSell(p, 280, 30000000000L)
      actor ! s
      actor ! b
      receiveN(2)

      actor ! GetAskOrdersRequest
      val restSAmount = Order.correctAmount(700000L, 280)
      val restAmount  = 30000000000L - restSAmount
      val restFee     = s.matcherFee - LimitOrder.getPartialFee(s.matcherFee, s.amount, restSAmount)
      expectMsg(GetOrdersResponse(Seq(SellLimitOrder(s.price, restAmount, restFee, s))))

      actor ! GetBidOrdersRequest
      expectMsg(GetOrdersResponse(Seq.empty))
    }

    "cancel expired orders after OrderCleanup command" in obcTest { (pair, actor) =>
      val time   = NTP.correctedTime()
      val price  = 34118
      val amount = 1

      val expiredOrder = buy(pair, price, amount).copy(expiration = time)
      actor ! expiredOrder
      receiveN(1)
      getOrders(actor) shouldEqual Seq(BuyLimitOrder(price * Order.PriceConstant, amount, expiredOrder.matcherFee, expiredOrder))
      actor ! OrderCleanup
      expectMsg(OrderCanceled(expiredOrder.id()))
      getOrders(actor).size should be(0)
    }

    "preserve valid orders after OrderCleanup command" in obcTest { (pair, actor) =>
      val price  = 34118
      val amount = 1

      val order          = buy(pair, price, amount)
      val expectedOrders = Seq(BuyLimitOrder(price * Order.PriceConstant, amount, order.matcherFee, order))

      actor ! order
      receiveN(1)
      getOrders(actor) shouldEqual expectedOrders
      actor ! OrderCleanup
      getOrders(actor) shouldEqual expectedOrders
    }

    "responsd with a error after timeout" in obcTest { (pair, actor) =>
      val actor: ActorRef = createOrderBookActor(TestProbe().ref, 50.millis)

      val order = buy(pair, 1, 1)
      actor ! order
      Thread.sleep(60)
      expectMsg(OperationTimedOut)
    }

    "ignore an unexpected validation message" when {
      "receives ValidateOrderResult of another order" in {
        val historyActor = TestProbe()
        val actor        = createOrderBookActor(historyActor.ref)

        val order = buy(defaultPair, 1, 1)
        actor ! order

        val unexpectedOrder = buy(defaultPair, 1, 2)
        actor.tell(ValidateOrderResult(unexpectedOrder.id(), Right(unexpectedOrder)), historyActor.ref)
        expectNoMessage()
      }
    }
  }

  private def createOrderBookActor(historyActor: ActorRef, validationTimeout: FiniteDuration = 10.minutes): ActorRef = system.actorOf(
    Props(
      new OrderBookActor(
        defaultPair,
        _ => (),
        historyActor,
        stub[Blockchain],
        stub[Wallet],
        stub[UtxPool],
        stub[ChannelGroup],
        settings.copy(validationTimeout = validationTimeout),
        FunctionalitySettings.TESTNET
      ) with RestartableActor
    )
  )

}