package com.luiz.commit_mirror.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** A fixed clock that only moves when a test calls {@link #advance(Duration)}; {@link #reset()} rewinds it. */
public final class MutableClock extends Clock {

	private final Instant initial;

	private final AtomicReference<Instant> now;

	private final ZoneId zone;

	public MutableClock(Instant initial) {
		this(initial, new AtomicReference<>(initial), ZoneId.of("UTC"));
	}

	private MutableClock(Instant initial, AtomicReference<Instant> now, ZoneId zone) {
		this.initial = initial;
		this.now = now;
		this.zone = zone;
	}

	public void advance(Duration duration) {
		now.updateAndGet(current -> current.plus(duration));
	}

	public void reset() {
		now.set(initial);
	}

	@Override
	public Instant instant() {
		return now.get();
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(initial, now, zone);
	}

}
