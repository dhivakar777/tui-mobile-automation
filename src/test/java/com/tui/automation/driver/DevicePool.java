package com.tui.automation.driver;

import com.tui.automation.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Hands out devices to concurrent scenarios when {@code device.udids} lists more than one target. Each device
 * carries its own UiAutomator2 {@code systemPort}, so two sessions never share a device or a port. A thread claims
 * a device before its session starts and returns it when the session ends. With {@code device.udids} blank the
 * pool is disabled and the single {@code device.udid} setting applies, as before.
 */
final class DevicePool {

    record Device(String udid, int systemPort) {
    }

    private static final Logger LOG = LogManager.getLogger(DevicePool.class);
    private static final int BASE_SYSTEM_PORT = 8200;
    private static final long CLAIM_TIMEOUT_MINUTES = 10;
    private static final List<Device> DEVICES = configuredDevices();
    private static final BlockingQueue<Device> FREE = new LinkedBlockingQueue<>(DEVICES);
    private static final ThreadLocal<Device> CLAIMED = new ThreadLocal<>();

    private DevicePool() {
    }

    static boolean isEnabled() {
        return !DEVICES.isEmpty();
    }

    /** Takes a free device, waiting while all are in use. */
    static Device claim() {
        if (CLAIMED.get() != null) {
            throw new IllegalStateException("Thread " + Thread.currentThread().getName() + " already holds device "
                    + CLAIMED.get().udid());
        }
        try {
            Device device = FREE.poll(CLAIM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (device == null) {
                throw new IllegalStateException("No device became free within " + CLAIM_TIMEOUT_MINUTES
                        + " minutes; is parallel.threads larger than the number of devices in device.udids?");
            }
            CLAIMED.set(device);
            LOG.info("Claimed device {} (systemPort {})", device.udid(), device.systemPort());
            return device;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free device", e);
        }
    }

    /** Returns this thread's device to the pool. Safe to call when nothing is held. */
    static void release() {
        Device device = CLAIMED.get();
        if (device == null) {
            return;
        }
        CLAIMED.remove();
        FREE.add(device);
        LOG.info("Released device {}", device.udid());
    }

    private static List<Device> configuredDevices() {
        List<String> udids = ConfigManager.getOptional("device.udids")
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        if (!udids.isEmpty() && ConfigManager.getOptional("device.udid").isPresent()) {
            throw new IllegalStateException("Set either device.udid or device.udids, not both");
        }
        if (udids.stream().distinct().count() != udids.size()) {
            throw new IllegalStateException("device.udids lists the same device more than once: " + udids);
        }
        return IntStream.range(0, udids.size())
                .mapToObj(i -> new Device(udids.get(i), BASE_SYSTEM_PORT + i))
                .toList();
    }
}
