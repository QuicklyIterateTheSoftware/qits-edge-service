package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;

/** The two durable-log operations the edge startup barrier needs. */
public interface DeploymentProjectionCatchup {

  CatchupResult rebuildFromEpoch(String consumerId);

  CatchupResult catchUp(String consumerId);
}
