package com.initialneko.cdp;

public final class CaptureOptions {
    private boolean captureRequestBody = true;
    private boolean captureResponseBody = true;
    private int maxBodyChars = 2_000_000;

    public static CaptureOptions defaults() {
        return new CaptureOptions();
    }

    public boolean isCaptureRequestBody() {
        return captureRequestBody;
    }

    public CaptureOptions captureRequestBody(boolean captureRequestBody) {
        this.captureRequestBody = captureRequestBody;
        return this;
    }

    public boolean isCaptureResponseBody() {
        return captureResponseBody;
    }

    public CaptureOptions captureResponseBody(boolean captureResponseBody) {
        this.captureResponseBody = captureResponseBody;
        return this;
    }

    public int getMaxBodyChars() {
        return maxBodyChars;
    }

    public CaptureOptions maxBodyChars(int maxBodyChars) {
        if (maxBodyChars <= 0) {
            throw new IllegalArgumentException("maxBodyChars must be > 0");
        }
        this.maxBodyChars = maxBodyChars;
        return this;
    }
}
