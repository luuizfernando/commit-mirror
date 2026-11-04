package com.luiz.commit_mirror.domain;

import java.time.LocalDate;
import java.util.List;

public record MostActiveDays(List<LocalDate> dates, long commits) {

	public MostActiveDays {
		dates = List.copyOf(dates);
	}

}
