package globalquake.core.database;

import edu.sc.seis.seisFile.seedlink.SeedlinkReader;
import gqserver.api.packets.station.InputType;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SeedlinkCommunicator {

    public static final long UNKNOWN_DELAY = Long.MIN_VALUE;

    // Legacy SeedLink v3.x timestamp formats (no zone in the string; both are UTC).
    // DateTimeFormatter is immutable and thread-safe, so no ThreadLocal is needed.
    private static final DateTimeFormatter FORMAT_UTC_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FORMAT_UTC_LONG = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSSS");
    private static final long MAX_DELAY_MS = 1000 * 60 * 60 * 24L;

    public static void runAvailabilityCheck(SeedlinkNetwork seedlinkNetwork, StationDatabase stationDatabase, int attempt) throws Exception {
        if(attempt > 1){
            Logger.warn("Attempt %d / %d to obtain available stations from %s".formatted(attempt, StationDatabaseManager.ATTEMPTS, seedlinkNetwork.getName()));
        }

        seedlinkNetwork.setStatus(0, attempt == 1 ? "Connecting..." : "Connecting... (attempt %d / %d)".formatted(attempt, StationDatabaseManager.ATTEMPTS));
        SeedlinkReader reader = new SeedlinkReader(seedlinkNetwork.getHost(), seedlinkNetwork.getPort(), seedlinkNetwork.getTimeout(), false, seedlinkNetwork.getTimeout());

        seedlinkNetwork.setStatus(33, "Downloading...");
        String infoString = reader.getInfoString(SeedlinkReader.INFO_STREAMS).trim().replaceAll("[^\\u0009\\u000a\\u000d\\u0020-\\uD7FF\\uE000-\\uFFFD]", " ");

        seedlinkNetwork.setStatus(66, "Parsing...");
        parseAvailability(infoString, stationDatabase, seedlinkNetwork);

        seedlinkNetwork.setStatus(80, "Finishing...");
        reader.close();

        seedlinkNetwork.setStatus(99, "Done");
    }

    private static void parseAvailability(String infoString, StationDatabase stationDatabase, SeedlinkNetwork seedlinkNetwork) throws Exception {
        seedlinkNetwork.availableStations = 0;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new InputSource(new StringReader(infoString)));
        doc.getDocumentElement().normalize();
        NodeList nodeList = doc.getElementsByTagName("station");
        Logger.info("Found %d available stations in seedlink %s".formatted(nodeList.getLength(), seedlinkNetwork.getName()));
        for (int itr = 0; itr < nodeList.getLength(); itr++) {
            Node node = nodeList.item(itr);
            String stationCode = node.getAttributes().getNamedItem("name").getTextContent();
            String networkCode = node.getAttributes().getNamedItem("network").getTextContent();
            NodeList channelList = ((Element) node).getElementsByTagName("stream");
            for (int k = 0; k < channelList.getLength(); k++) {
                Node channel = channelList.item(k);
                String locationCode = channel.getAttributes().getNamedItem("location").getTextContent();
                String channelName = channel.getAttributes().getNamedItem("seedname").getTextContent();
                String endDate = channel.getAttributes().getNamedItem("end_time").getTextContent();

                long delay = UNKNOWN_DELAY;

                try {
                    delay = System.currentTimeMillis() - parseEndTime(endDate);

                    if (delay > MAX_DELAY_MS) {
                        continue;
                    }

                } catch(Exception e){
                    // A single unparseable/unexpected end_time must only affect this one channel
                    // (it degrades to UNKNOWN_DELAY), never abort discovery for the whole network.
                    Logger.warn("Failed to get delay from %s, %s: %s".formatted(stationCode, seedlinkNetwork.getName(), e.getMessage()));
                }

                addAvailableChannel(networkCode, stationCode, channelName, locationCode, delay, seedlinkNetwork, stationDatabase);
            }
        }
    }

    /**
     * Parses a seedlink {@code end_time} attribute into epoch milliseconds (UTC).
     * Supports the SeedLink v4 / RingServer &ge;4.0 ISO-8601 form
     * ({@code 2026-07-09T09:24:18.060000Z}) as well as the two legacy SeedLink v3.x formats.
     * Throws if the value matches none of them; callers treat that as {@link #UNKNOWN_DELAY}.
     */
    private static long parseEndTime(String endDate) {
        // SeedLink v4 (%Y-%m-%dT%H:%M:%S.%fZ): ISO-8601 with a 'T' separator and 'Z' zone suffix.
        // Instant.parse tolerates the variable fractional-second width RingServer emits.
        if (endDate.indexOf('T') >= 0) {
            return Instant.parse(endDate).toEpochMilli();
        }

        // Legacy SeedLink v3.x formats (implicitly UTC): pick by separator as before.
        DateTimeFormatter format = endDate.indexOf('-') >= 0 ? FORMAT_UTC_SHORT : FORMAT_UTC_LONG;
        return LocalDateTime.parse(endDate, format).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static void addAvailableChannel(String networkCode, String stationCode, String channelName, String locationCode, long delay, SeedlinkNetwork seedlinkNetwork, StationDatabase stationDatabase) {
        locationCode = locationCode.trim();
        stationDatabase.getDatabaseWriteLock().lock();
        try {
            Station station = StationDatabase.getStation(stationDatabase.getNetworks(), networkCode, stationCode);
            if(station == null){
                return; // :(
            }

            Channel channel = StationDatabase.getChannel(stationDatabase.getNetworks(), networkCode, stationCode, channelName, locationCode);

            if(channel == null){
                channel = findChannelButDontUseLocationCode(station, channelName);

                if(channel != null){
                    var any = channel.getStationSources().stream().findAny();
                    Channel newChannel = StationDatabase.getOrCreateChannel(station, channelName, locationCode, channel.getLatitude(), channel.getLongitude(), channel.getElevation(), channel.getSampleRate(), any.orElse(null), -1, InputType.UNKNOWN);
                    Logger.warn("Did not find exact match for [%s %s %s `%s`], assuming the location code is `%s`".formatted(networkCode, stationCode, channelName, locationCode, channel.getLocationCode()));
                    channel = newChannel;
                }
            }

            if (channel == null) {
                return;
            }

            seedlinkNetwork.availableStations++;
            channel.getSeedlinkNetworks().put(seedlinkNetwork, delay);
            channel.rememberSeedlink(seedlinkNetwork); // persist so live data survives a restart before the next scan
        }finally {
            stationDatabase.getDatabaseWriteLock().unlock();
        }
    }

    private static Channel findChannelButDontUseLocationCode(Station station, String channelName) {
        List<Channel> channels = new ArrayList<>(station.getChannels()).stream().filter(channel -> channel.getCode().equals(channelName)).sorted(Comparator.comparing(Channel::getLocationCode)).toList();
        if(channels.isEmpty()){
            return null;
        }

        return channels.get(0);
    }

}
