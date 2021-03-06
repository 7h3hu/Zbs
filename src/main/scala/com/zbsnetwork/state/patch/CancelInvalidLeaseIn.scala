package com.zbsnetwork.state.patch

import com.zbsnetwork.common.utils.EitherExt2
import com.zbsnetwork.state.{Diff, _}
import com.zbsnetwork.utils.ScorexLogging

object CancelInvalidLeaseIn extends ScorexLogging {
  def apply(blockchain: Blockchain): Diff = {
    log.info("Collecting lease in overflows")

    val allActiveLeases = blockchain.allActiveLeases

    log.info(s"Collected ${allActiveLeases.size} active leases")

    val leaseInBalances = allActiveLeases.toSeq
      .map(lt => blockchain.resolveAlias(lt.recipient).explicitGet() -> lt.amount)
      .groupBy(_._1)
      .mapValues(_.map(_._2).sum)

    log.info("Calculated active lease in balances")

    val diff = blockchain.collectLposPortfolios {
      case (addr, p) if p.lease.in != leaseInBalances.getOrElse(addr, 0L) =>
        log.info(s"$addr: actual = ${leaseInBalances.getOrElse(addr, 0L)}, stored: ${p.lease.in}")
        Portfolio(0, LeaseBalance(leaseInBalances.getOrElse(addr, 0L) - p.lease.in, 0), Map.empty)
    }

    log.info("Finished collecting lease in overflows")

    Diff.empty.copy(portfolios = diff)
  }
}
