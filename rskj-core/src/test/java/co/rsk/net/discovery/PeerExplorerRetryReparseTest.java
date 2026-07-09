/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.net.discovery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.rsk.net.discovery.table.KademliaOptions;
import co.rsk.net.discovery.table.NodeDistanceTable;
import co.rsk.scoring.PeerScoringManager;
import co.rsk.util.IpUtils;
import org.ethereum.crypto.ECKey;
import org.ethereum.net.rlpx.Node;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;

/**
 * M7u — drives the real {@code PeerExplorer.update()} retry loop synchronously with an
 * all-malformed boot list and a bounded {@code maxBootRetries}, asserting the retry counter
 * progression, the terminal "max retry attempts reached." log, and the per-retry WARN
 * re-emission multiplier on the {@code co.rsk.util.IpUtils} logger.
 *
 * <p>The harness deliberately does NOT stub/spy the parse path: {@code update()} must exercise the
 * real {@code loadInitialBootNodes} -> {@code IpUtils.parseAddresses} re-parse.</p>
 */
class PeerExplorerRetryReparseTest {

    private static final String HOST = "localhost";
    private static final int PORT = 44035;

    private static final long TIMEOUT = 30000;
    private static final long UPDATE = 60000;
    private static final long CLEAN = 60000;
    private static final int NETWORK_ID = 1;

    @Test
    void updateReParsesMalformedBootNodesAmplifyingWarnsUntilRetryLimit() {
        // all-malformed boot list: both entries are numeric-but-invalid ports, so each parse
        // emits exactly one WARN per entry and bootNodes stays empty (retry branch stays live).
        List<String> malformed = new ArrayList<>();
        malformed.add("badhost:65536");                     // out-of-range port
        malformed.add("overhost:999999999999999999999");     // over-int port
        int malformedCount = malformed.size();
        int maxBootRetries = 2;

        Node node = new Node(new ECKey().getNodeId(), HOST, PORT);
        NodeDistanceTable distanceTable = new NodeDistanceTable(KademliaOptions.BINS, KademliaOptions.BUCKET_SIZE, node);

        // construct FIRST (this triggers the one-shot ctor parse), then attach the appenders so
        // that only the retry-phase re-parses are counted -> WARN count == retries x malformedCount.
        PeerExplorer peerExplorer = new PeerExplorer(malformed, node, distanceTable, new ECKey(),
                TIMEOUT, UPDATE, CLEAN, NETWORK_ID, mock(PeerScoringManager.class), true, maxBootRetries);
        peerExplorer.setUDPChannel(Mockito.mock(UDPChannel.class));

        Logger ipUtilsLogger = (Logger) LoggerFactory.getLogger(IpUtils.class);
        Logger peerExplorerLogger = (Logger) LoggerFactory.getLogger(PeerExplorer.class);
        ListAppender<ILoggingEvent> ipUtilsAppender = new ListAppender<>();
        ListAppender<ILoggingEvent> peerExplorerAppender = new ListAppender<>();
        ipUtilsAppender.start();
        peerExplorerAppender.start();
        ipUtilsLogger.addAppender(ipUtilsAppender);
        peerExplorerLogger.addAppender(peerExplorerAppender);

        try {
            peerExplorer.start(); // -> RUNNING; startConversationWithNewNodes is a no-op (bootNodes empty)
            Assertions.assertEquals(0, peerExplorer.getRetryCounter());

            // first two calls take the retry branch and re-parse; counter steps 0 -> 1 -> 2
            peerExplorer.update();
            Assertions.assertEquals(1, peerExplorer.getRetryCounter());

            peerExplorer.update();
            Assertions.assertEquals(2, peerExplorer.getRetryCounter());

            // third call hits the limit: no re-parse, counter frozen, terminal log emitted
            peerExplorer.update();
            Assertions.assertEquals(2, peerExplorer.getRetryCounter());

            // WARN amplification: each of the 2 retries re-parsed the whole malformed list
            long ipUtilsWarns = ipUtilsAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .count();
            Assertions.assertEquals((long) maxBootRetries * malformedCount, ipUtilsWarns);

            // terminal branch logged exactly the expected message on the PeerExplorer logger
            List<ILoggingEvent> terminalWarns = peerExplorerAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("max retry attempts reached."))
                    .collect(Collectors.toList());
            Assertions.assertEquals(1, terminalWarns.size());
        } finally {
            ipUtilsLogger.detachAppender(ipUtilsAppender);
            peerExplorerLogger.detachAppender(peerExplorerAppender);
            ipUtilsAppender.stop();
            peerExplorerAppender.stop();
            peerExplorer.dispose();
        }
    }
}
