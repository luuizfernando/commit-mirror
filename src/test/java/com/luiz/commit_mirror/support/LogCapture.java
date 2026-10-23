package com.luiz.commit_mirror.support;

import java.util.Arrays;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures the events of one class logger at DEBUG and up. {@link #detach()} restores the logger exactly as it was,
 * so no test leaks logging state into another.
 */
public final class LogCapture {

	private final Logger logger;

	private final Level previousLevel;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private LogCapture(Class<?> type) {
		this.logger = (Logger) LoggerFactory.getLogger(type);
		this.previousLevel = logger.getLevel();
	}

	public static LogCapture attach(Class<?> type) {
		LogCapture capture = new LogCapture(type);
		capture.logger.setLevel(Level.DEBUG);
		capture.appender.start();
		capture.logger.addAppender(capture.appender);
		return capture;
	}

	public void detach() {
		logger.detachAppender(appender);
		logger.setLevel(previousLevel);
	}

	public boolean hasEventAt(Level level) {
		return appender.list.stream().anyMatch(event -> event.getLevel() == level);
	}

	public boolean hasErrorWithThrowableMessage(String message) {
		return appender.list.stream()
			.anyMatch(event -> event.getLevel() == Level.ERROR && event.getThrowableProxy() != null
					&& message.equals(event.getThrowableProxy().getMessage()));
	}

	/** Looks at the formatted message and at every raw argument, so a value passed but not rendered still counts. */
	public boolean anyEventContains(String text) {
		return appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(text)
				|| (event.getArgumentArray() != null
						&& Arrays.stream(event.getArgumentArray()).anyMatch(arg -> String.valueOf(arg).contains(text))));
	}

}
