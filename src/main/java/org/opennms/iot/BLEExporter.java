package org.opennms.iot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Localizable;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.Messages;
import org.opennms.iot.ble.proto.BLEExporterGrpc;
import org.opennms.iot.handlers.TICC2650Handler;
import org.opennms.iot.handlers.PolarH7Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import tinyb.BluetoothDevice;
import tinyb.BluetoothException;
import tinyb.BluetoothGattCharacteristic;
import tinyb.BluetoothGattService;
import tinyb.BluetoothManager;

public class BLEExporter {
    private static final Logger LOG = LoggerFactory.getLogger(BLEExporter.class);

    private Server server;

    @Option(name="-port",usage="gRPC Server port")
    private int port = 9002;

    @Argument
    private List<String> arguments = new ArrayList<>();

    private BLEExporterImpl bleExporterSvc = new BLEExporterImpl();

    private List<SensorTracker> sensorTrackers = new LinkedList<>();

    private boolean running = true;

    public static void main(String[] args) throws Exception {
        new BLEExporter().doMain(args);
    }

    public void doMain(String[] args) throws Exception {
        ParserProperties parserProperties = ParserProperties.defaults()
                .withUsageWidth(80);
        CmdLineParser parser = new CmdLineParser(this, parserProperties);

        try {
            parser.parseArgument(args);
            if( arguments.isEmpty() ) {
                throw new CmdLineException(parser, "At least one device address is required.");
            }
        } catch( CmdLineException e ) {
            System.err.println(e.getMessage());
            System.err.println("ble_exporter [options...] arguments...");
            // print the list of available options
            parser.printUsage(System.err);
            System.err.println();
            return;
        }
        NativeLibrary.load();

        /*
         * To start looking of the device, we first must initialize the TinyB library. The way of interacting with the
         * library is through the BluetoothManager. There can be only one BluetoothManager at one time, and the
         * reference to it is obtained through the getBluetoothManager method.
         */
        BluetoothManager manager = BluetoothManager.getBluetoothManager();

        /*
         * The manager will try to initialize a BluetoothAdapter if any adapter is present in the system. To initialize
         * discovery we can call startDiscovery, which will put the default adapter in discovery mode.
         */
        boolean discoveryStarted = manager.startDiscovery();
        LOG.info("Discovery started: {}", discoveryStarted);

        // Now start the gRPC service
        startGrpcServer();

        sensorTrackers = arguments.stream()
                .map(SensorTracker::new)
                .collect(Collectors.toList());

        Lock lock = new ReentrantLock();
        Condition cv = lock.newCondition();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                running = false;
                lock.lock();
                try {
                    cv.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        });

        sensorTrackers.forEach(SensorTracker::start);

        // TODO: Eventually stop discovery
        //  manager.stopDiscovery();

        // Wait until stopped
        LOG.info("Waiting...");
        while (running) {
            lock.lock();
            try {
                cv.await(1, TimeUnit.SECONDS);
            } finally {
                lock.unlock();
            }
        }
        sensorTrackers.forEach(SensorTracker::stop);
    }

    private void startGrpcServer() throws IOException {
        /* The port on which the server should run */
        server = ServerBuilder.forPort(port)
                .addService(bleExporterSvc)
                .build()
                .start();
        LOG.info("Server started, listening on: {}", port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                LOG.info("Shutting down gRPC (JVM going down).");
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    BLEExporter.this.stopGrpcServer();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
                LOG.info("gRPC is gone.");
            }
        });
    }

    private void stopGrpcServer() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

}