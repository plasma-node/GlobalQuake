package globalquake.core.seedlink;

import edu.sc.seis.seisFile.mseed.DataRecord;
import edu.sc.seis.seisFile.mseed.SeedFormatException;
import edu.sc.seis.seisFile.seedlink.SeedlinkException;
import edu.sc.seis.seisFile.seedlink.SeedlinkPacket;
import edu.sc.seis.seisFile.seedlink.SeedlinkReader;
import globalquake.core.GlobalQuake;
import globalquake.core.database.SeedlinkNetwork;
import globalquake.core.database.SeedlinkStatus;
import globalquake.core.station.AbstractStation;
import globalquake.core.station.GlobalStation;
import org.tinylog.Logger;

import java.net.SocketException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SeedlinkNetworksReader {

	protected static final int RECONNECT_DELAY = 10;
	private static final int SEEDLINK_TIMEOUT = 90;

	// The seedlink handshake costs two blocking round-trips PER STATION (STATION cmd + SELECT cmd),
	// and no data flows until the whole connection finishes handshaking. Large catalogs (IRIS
	// rtserve carries hundreds of selected stations since the SeedLink-v4 discovery fix) therefore
	// took minutes before their first packet, which showed up as the bottom-left station counter
	// crawling. Splitting each network across several parallel connections amortizes the
	// round-trips; kept conservative so we don't hammer public servers with connections.
	private static final int MAX_STATIONS_PER_CONNECTION = 32;
	private static final int MAX_CONNECTIONS_PER_NETWORK = 8;

	private Instant lastData;

	private ExecutorService seedlinkReaderService;

	private final Queue<SeedlinkReader> activeReaders = new ConcurrentLinkedQueue<>();
	private final Map<SeedlinkNetwork, java.util.concurrent.atomic.AtomicInteger> activeConnections = new java.util.concurrent.ConcurrentHashMap<>();

	public static void main(String[] args) throws Exception{
		SeedlinkReader reader = new SeedlinkReader("rtserve.iris.washington.edu", 18000);
		reader.selectData("AK", "D25K", List.of("BHZ"));
		reader.endHandshake();

		SortedSet<DataRecord> set = new TreeSet<>(Comparator.comparing(dataRecord -> dataRecord.getStartBtime().toInstant().toEpochMilli()));

		while(reader.hasNext() && set.size() < 10){
			SeedlinkPacket pack = reader.readPacket();
			DataRecord dataRecord = pack.getMiniSeed();
			System.out.println(pack.getMiniSeed().getStartTime()+" - "+pack.getMiniSeed().getLastSampleTime()+" x "+pack.getMiniSeed().getEndTime()+" @ "+pack.getMiniSeed().getSampleRate());
			System.out.println(pack.getMiniSeed().getControlHeader().getSequenceNum());
			if(!set.add(dataRecord)){
				System.out.println("ERR ALREADY CONTAINS");
			}
		}

		reader.close();
		for(DataRecord dataRecord : set){
			System.err.println(dataRecord.getStartTime()+" - "+dataRecord.getLastSampleTime()+" x "+dataRecord.getEndTime()+" @ "+dataRecord.getSampleRate());
			System.err.println(dataRecord.oneLineSummary());
		}
	}

	public void run() {
		createCache();
		seedlinkReaderService = Executors.newCachedThreadPool();
		GlobalQuake.instance.getStationDatabaseManager().getStationDatabase().getDatabaseReadLock().lock();

		try{
			GlobalQuake.instance.getStationDatabaseManager().getStationDatabase().getSeedlinkNetworks().forEach(this::startSeedlinkNetwork);
		} finally {
			GlobalQuake.instance.getStationDatabaseManager().getStationDatabase().getDatabaseReadLock().unlock();
		}
	}

	private void startSeedlinkNetwork(SeedlinkNetwork seedlinkNetwork) {
		List<AbstractStation> networkStations = new ArrayList<>();
		for (AbstractStation station : GlobalQuake.instance.getStationManager().getStations()) {
			if (station.getSeedlinkNetwork() != null && station.getSeedlinkNetwork().equals(seedlinkNetwork)) {
				networkStations.add(station);
			}
		}

		seedlinkNetwork.status = SeedlinkStatus.CONNECTING;
		seedlinkNetwork.connectedStations = 0;

		if (networkStations.isEmpty()) {
			Logger.info("No stations selected on " + seedlinkNetwork.getName());
			seedlinkNetwork.status = SeedlinkStatus.DISCONNECTED;
			return;
		}

		int connections = Math.min(MAX_CONNECTIONS_PER_NETWORK,
				(networkStations.size() + MAX_STATIONS_PER_CONNECTION - 1) / MAX_STATIONS_PER_CONNECTION);
		activeConnections.put(seedlinkNetwork, new java.util.concurrent.atomic.AtomicInteger(connections));

		// deal stations round-robin so each connection gets an even share
		List<List<AbstractStation>> chunks = new ArrayList<>();
		for (int i = 0; i < connections; i++) {
			chunks.add(new ArrayList<>());
		}
		for (int i = 0; i < networkStations.size(); i++) {
			chunks.get(i % connections).add(networkStations.get(i));
		}

		Logger.info("Connecting to seedlink server \"%s\" using %d connection(s) for %d stations"
				.formatted(seedlinkNetwork.getName(), connections, networkStations.size()));
		for (List<AbstractStation> chunk : chunks) {
			seedlinkReaderService.submit(() -> runSeedlinkThread(seedlinkNetwork, chunk, RECONNECT_DELAY));
		}
	}

	private final Map<String, GlobalStation> stationCache = new HashMap<>();

	private void createCache() {
		for (AbstractStation s : GlobalQuake.instance.getStationManager().getStations()) {
			if (s instanceof GlobalStation) {
				stationCache.put("%s %s".formatted(s.getNetworkCode(), s.getStationCode()), (GlobalStation) s);
			}
		}
	}
	private void runSeedlinkThread(SeedlinkNetwork seedlinkNetwork, List<AbstractStation> chunk, int reconnectDelay) {
		int chunkConnected = 0;
		SeedlinkReader reader = null;
		try {
			reader = new SeedlinkReader(seedlinkNetwork.getHost(), seedlinkNetwork.getPort(), SEEDLINK_TIMEOUT, false, SEEDLINK_TIMEOUT);
			activeReaders.add(reader);

			reader.sendHello();

			reconnectDelay = RECONNECT_DELAY; // if connect succeeded then reset the delay

			int errors = 0;

			for (AbstractStation station : chunk) {
				Logger.trace("Connecting to %s %s %s %s [%s]".formatted(station.getStationCode(), station.getNetworkCode(), station.getChannelName(), station.getLocationCode(), seedlinkNetwork.getName()));
				try {
					reader.selectData(station.getNetworkCode(), station.getStationCode(), List.of("%s%s".formatted(station.getLocationCode(),
							station.getChannelName())));
					chunkConnected++;
					synchronized (seedlinkNetwork) {
						seedlinkNetwork.connectedStations++;
					}
				}catch(SeedlinkException seedlinkException){
					Logger.warn("Unable to connect to %s %s %s %s [%s]!".formatted(station.getStationCode(), station.getNetworkCode(), station.getChannelName(), station.getLocationCode(), seedlinkNetwork.getName()));
					errors++;
					if(errors > chunk.size() * 0.1){
						Logger.warn("Too many errors in seedlink network %s, resetting!".formatted(seedlinkNetwork.getName()));
						throw seedlinkException;
					}
				}
			}

			if(chunkConnected == 0){
				Logger.info("No stations connected to "+seedlinkNetwork.getName());
				var active = activeConnections.get(seedlinkNetwork);
				if (active == null || active.decrementAndGet() <= 0) {
					seedlinkNetwork.status = SeedlinkStatus.DISCONNECTED;
				}
				return;
			}

			reader.endHandshake();
			seedlinkNetwork.status = SeedlinkStatus.RUNNING;

			while (reader.hasNext()) {
				SeedlinkPacket slp = reader.readPacket();
				try {
					newPacket(slp.getMiniSeed());
				} catch(SocketException | SeedFormatException se){
					Logger.trace(se);
				} catch (Exception e) {
					Logger.error(e);
				}
			}

			reader.close();
		} catch (Exception e) {
			Logger.warn("Seedlink reader failed for seedlink `%s`: %s".formatted(seedlinkNetwork.getName(), e.getMessage()));
		} finally {
			if(reader != null){
				try {
					reader.close();
				} catch (Exception ex) {
					Logger.error(ex);
				}
				activeReaders.remove(reader);
			}
			if (chunkConnected > 0) {
				synchronized (seedlinkNetwork) {
					seedlinkNetwork.connectedStations -= chunkConnected;
				}
			}
		}

		// only the last connection of this network to die flips it to DISCONNECTED
		var active = activeConnections.get(seedlinkNetwork);
		if (active == null || active.decrementAndGet() <= 0) {
			seedlinkNetwork.status = SeedlinkStatus.DISCONNECTED;
		}
		Logger.warn("A connection to %s died, reconnecting it after %d seconds...".formatted(seedlinkNetwork.getName(), reconnectDelay));

		try {
			Thread.sleep(reconnectDelay * 1000L);
			if(reconnectDelay < 60 * 5) {
				reconnectDelay *= 2;
			}
		} catch (InterruptedException ignored) {
			Logger.warn("Seedlink reader thread for %s interrupted".formatted(seedlinkNetwork.getName()));
			return;
		}

		if (active != null) {
			active.incrementAndGet();
		}
		if (seedlinkNetwork.status == SeedlinkStatus.DISCONNECTED) {
			seedlinkNetwork.status = SeedlinkStatus.CONNECTING;
		}
		int finalReconnectDelay = reconnectDelay;
		seedlinkReaderService.submit(() -> runSeedlinkThread(seedlinkNetwork, chunk, finalReconnectDelay));
	}

	private void newPacket(DataRecord dr) {
		if (lastData == null || dr.getLastSampleBtime().toInstant().isAfter(lastData)) {
			lastData = dr.getLastSampleBtime().toInstant();
		}

		String network = dr.getHeader().getNetworkCode().replaceAll(" ", "");
		String station = dr.getHeader().getStationIdentifier().replaceAll(" ", "");
		var globalStation = stationCache.get("%s %s".formatted(network, station));
		if(globalStation == null){
			Logger.trace("Seedlink sent data for %s %s, but that was never selected!".formatted(network, station));
		}else {
			globalStation.addRecord(dr);
		}
	}

	public void stop() {
		if(seedlinkReaderService != null) {
			seedlinkReaderService.shutdownNow();
            for (Iterator<SeedlinkReader> iterator = activeReaders.iterator(); iterator.hasNext(); ) {
                SeedlinkReader reader = iterator.next();
                reader.close();
            	iterator.remove();
			}
			try {
				if(!seedlinkReaderService.awaitTermination(10, TimeUnit.SECONDS)){
					Logger.error("Unable to terminate seedlinkReaderService!");
				}
			} catch (InterruptedException e) {
				Logger.error(e);
			}
		}
		stationCache.clear();
	}

}
