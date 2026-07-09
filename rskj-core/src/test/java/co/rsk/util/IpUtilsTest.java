package co.rsk.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by mario on 13/07/17.
 */
class IpUtilsTest {

    private static final String IPV6_WITH_PORT = "[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443";
    private static final String IPV6_NO_PORT = "[2001:db8:85a3:8d3:1319:8a2e:370:7348]";
    private static final String IPV6_INVALID = "2001:db8:85a3:8d3:1319:8a2e:370:7348";
    private static final String IPV4_WITH_PORT = "172.217.28.228:80";
    private static final String IPV4_NO_PORT = "172.217.28.228";
    private static final String HOSTNAME_WITH_PORT = "localhost:456";

    @Test
    void parseIPv6() {
        InetSocketAddress result = IpUtils.parseAddress(IPV6_WITH_PORT);
        Assertions.assertNotNull(result);
    }

    @Test
    void parseIPv6NoPort() {
        InetSocketAddress result = IpUtils.parseAddress(IPV6_NO_PORT);
        Assertions.assertNull(result);
    }

    @Test
    void parseIPv6InvalidFormat() {
        InetSocketAddress result = IpUtils.parseAddress(IPV6_INVALID);
        Assertions.assertNull(result);
    }

    @Test
    void parseIPv4() {
        InetSocketAddress result = IpUtils.parseAddress(IPV4_WITH_PORT);
        Assertions.assertNotNull(result);
    }

    @Test
    void parseIPv4NoPort() {
        InetSocketAddress result = IpUtils.parseAddress(IPV4_NO_PORT);
        Assertions.assertNull(result);
    }

    @Test
    void parseHostnameWithPort() {
        InetSocketAddress result = IpUtils.parseAddress(HOSTNAME_WITH_PORT);
        Assertions.assertNotNull(result);
    }

    @Test
    void parsePortAboveMaximum() {
        InetSocketAddress result = IpUtils.parseAddress("localhost:65536");
        Assertions.assertNull(result);
    }

    @Test
    void parsePortLargerThanInteger() {
        InetSocketAddress result = IpUtils.parseAddress("localhost:999999999999999999999");
        Assertions.assertNull(result);
    }

    @Test
    void parseAddresses() {
        List<String> addresses = new ArrayList<>();
        addresses.add(IPV6_WITH_PORT);
        addresses.add(IPV6_NO_PORT);
        addresses.add(IPV6_INVALID);
        addresses.add(IPV4_WITH_PORT);
        addresses.add(IPV4_NO_PORT);
        List<InetSocketAddress> result = IpUtils.parseAddresses(addresses);
        Assertions.assertFalse(result.isEmpty());
        Assertions.assertEquals(2, result.size());
    }

    // M3: a bad-port entry must not poison valid entries before OR after it,
    // nor reorder the survivors.
    @Test
    void parseAddressesSkipsInvalidPortsPreservingOrder() {
        List<String> addresses = new ArrayList<>();
        addresses.add(IPV4_WITH_PORT);                    // valid, port 80
        addresses.add("host:65536");                      // out-of-range port, skipped
        addresses.add(HOSTNAME_WITH_PORT);                // valid, port 456
        addresses.add("host:999999999999999999999");      // over-int port, skipped

        List<InetSocketAddress> result = IpUtils.parseAddresses(addresses);

        Assertions.assertEquals(2, result.size());
        // survivors kept in their ORIGINAL order: 80 came before 456 in the input
        Assertions.assertEquals(80, result.get(0).getPort());
        Assertions.assertEquals(456, result.get(1).getPort());
    }

    // M6: within a single parseAddresses call, exactly ONE WARN per malformed entry,
    // level pinned to WARN, ZERO WARN for valid entries, message carries host,
    // raw port string and a non-empty exception message.
    @Test
    void parseAddressesLogsExactlyOneWarnPerMalformedEntry() {
        Logger ipUtilsLogger = (Logger) LoggerFactory.getLogger(IpUtils.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ipUtilsLogger.addAppender(appender);
        try {
            List<String> addresses = new ArrayList<>();
            addresses.add(IPV4_WITH_PORT);                     // valid
            addresses.add("badhost:65536");                    // malformed: out-of-range
            addresses.add(HOSTNAME_WITH_PORT);                 // valid
            addresses.add("overhost:999999999999999999999");   // malformed: over-int

            List<InetSocketAddress> result = IpUtils.parseAddresses(addresses);
            Assertions.assertEquals(2, result.size());

            List<ILoggingEvent> warnEvents = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .collect(Collectors.toList());

            // exactly one WARN per malformed entry, and zero for the two valid ones
            Assertions.assertEquals(2, warnEvents.size());

            ILoggingEvent firstWarn = warnEvents.get(0);
            Assertions.assertEquals(Level.WARN, firstWarn.getLevel());
            String firstMsg = firstWarn.getFormattedMessage();
            Assertions.assertTrue(firstMsg.contains("badhost"), firstMsg);
            Assertions.assertTrue(firstMsg.contains("65536"), firstMsg);
            Assertions.assertNotNull(firstWarn.getArgumentArray());
            Assertions.assertFalse(((String) firstWarn.getArgumentArray()[2]).isEmpty(), firstMsg);

            ILoggingEvent secondWarn = warnEvents.get(1);
            Assertions.assertEquals(Level.WARN, secondWarn.getLevel());
            String secondMsg = secondWarn.getFormattedMessage();
            Assertions.assertTrue(secondMsg.contains("overhost"), secondMsg);
            Assertions.assertTrue(secondMsg.contains("999999999999999999999"), secondMsg);
            Assertions.assertFalse(((String) secondWarn.getArgumentArray()[2]).isEmpty(), secondMsg);
        } finally {
            ipUtilsLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
