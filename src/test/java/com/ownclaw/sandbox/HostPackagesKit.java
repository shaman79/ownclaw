package com.ownclaw.sandbox;

import java.nio.file.Path;

/** For tests outside this package: a HostPackages over a fake apt (HostPackagesTest.FakeApt). */
public final class HostPackagesKit {

    private HostPackagesKit() {}

    /** Packages fetched by a fake apt into {@code store}: tesseract's set unpacks a program, libraries and English. */
    public static HostPackages faked(Path store) {
        return new HostPackages(store, new HostPackagesTest.FakeApt());
    }
}
