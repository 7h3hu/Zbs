package com.zbsnetwork.state.appender

import cats.data.EitherT
import com.zbsnetwork.block.Block
import com.zbsnetwork.consensus.PoSSelector
import com.zbsnetwork.metrics._
import com.zbsnetwork.mining.Miner
import com.zbsnetwork.network._
import com.zbsnetwork.settings.ZbsSettings
import com.zbsnetwork.state.Blockchain
import com.zbsnetwork.transaction.ValidationError.{BlockAppendError, InvalidSignature}
import com.zbsnetwork.transaction.{BlockchainUpdater, ValidationError}
import com.zbsnetwork.utils.{ScorexLogging, Time}
import com.zbsnetwork.utx.UtxPool
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Right

object BlockAppender extends ScorexLogging with Instrumented {

  def apply(blockchainUpdater: BlockchainUpdater with Blockchain,
            time: Time,
            utxStorage: UtxPool,
            pos: PoSSelector,
            settings: ZbsSettings,
            scheduler: Scheduler,
            verify: Boolean = true)(newBlock: Block): Task[Either[ValidationError, Option[BigInt]]] =
    Task {
      measureSuccessful(
        blockProcessingTimeStats, {
          if (blockchainUpdater.isLastBlockId(newBlock.reference)) {
            appendBlock(blockchainUpdater, utxStorage, pos, time, settings, verify)(newBlock).map(_ => Some(blockchainUpdater.score))
          } else if (blockchainUpdater.contains(newBlock.uniqueId)) {
            Right(None)
          } else {
            Left(BlockAppendError("Block is not a child of the last block", newBlock))
          }
        }
      )
    }.executeOn(scheduler)

  def apply(blockchainUpdater: BlockchainUpdater with Blockchain,
            time: Time,
            utxStorage: UtxPool,
            pos: PoSSelector,
            settings: ZbsSettings,
            allChannels: ChannelGroup,
            peerDatabase: PeerDatabase,
            miner: Miner,
            scheduler: Scheduler)(ch: Channel, newBlock: Block): Task[Unit] = {
    BlockStats.received(newBlock, BlockStats.Source.Broadcast, ch)
    blockReceivingLag.safeRecord(System.currentTimeMillis() - newBlock.timestamp)
    (for {
      _                <- EitherT(Task.now(newBlock.signaturesValid()))
      validApplication <- EitherT(apply(blockchainUpdater, time, utxStorage, pos, settings, scheduler)(newBlock))
    } yield validApplication).value.map {
      case Right(None) => // block already appended
      case Right(Some(_)) =>
        BlockStats.applied(newBlock, BlockStats.Source.Broadcast, blockchainUpdater.height)
        log.debug(s"${id(ch)} Appended $newBlock")
        if (newBlock.transactionData.isEmpty)
          allChannels.broadcast(BlockForged(newBlock), Some(ch))
        miner.scheduleMining()
      case Left(is: InvalidSignature) =>
        peerDatabase.blacklistAndClose(ch, s"Could not append $newBlock: $is")
      case Left(ve) =>
        BlockStats.declined(newBlock, BlockStats.Source.Broadcast)
        log.debug(s"${id(ch)} Could not append $newBlock: $ve")
    }
  }

  private val blockReceivingLag        = Kamon.histogram("block-receiving-lag")
  private val blockProcessingTimeStats = Kamon.histogram("single-block-processing-time")

}
