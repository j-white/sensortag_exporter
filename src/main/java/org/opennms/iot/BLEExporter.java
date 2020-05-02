package org.opennms.iot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
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
    private List<String> arguments = new ArrayList<String>();

    private BLEExporterImpl bleExporterSvc = new BLEExporterImpl();

    private boolean running = true;

    public static void main(String[] args) throws Exception {
        new BLEExporter().doMain(args);
    }

    public void doMain(String[] args) throws Exception {
        NativeLibrary.load();

        if (args.length < 1) {
            System.err.println("Run with <device_address> argument");
            System.exit(-1);
        }

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

        BluetoothDevice sensor = Bluetooth.getDevice(arguments.get(0));

        /*
         * After we find the device we can stop looking for other devices.
         */
        try {
            manager.stopDiscovery();
        } catch (BluetoothException e) {
            System.err.println("Discovery could not be stopped.");
        }

        if (sensor == null) {
            System.err.println("No sensor found with the provided address.");
            System.exit(-1);
        }

        System.out.print("Found device: ");
        Bluetooth.printDevice(sensor);

        if (sensor.connect())
            System.out.println("Sensor with the provided address connected");
        else {
            System.out.println("Could not connect device.");
            System.exit(-1);
        }

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


        Handler handler;
        // FIXME - we need a nicer way to register these
        if (TICC2650Handler.handles(sensor)) {
            handler = new TICC2650Handler(sensor);
        } else if (PolarH7Handler.handles(sensor)) {
            handler = new PolarH7Handler(sensor);
        } else {
            throw new UnsupportedOperationException("Unsupported sensor :(");
        }

        handler.registerConsumer(bleExporterSvc::broadcast);
        handler.startGatheringData();



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
        sensor.disconnect();
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