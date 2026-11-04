package com.luiz.commit_mirror.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MostActiveDaysTest {

	@Test
	void shouldNotReflectChangesMadeToTheOriginalDatesList() {
		List<LocalDate> dates = new ArrayList<>(List.of(LocalDate.of(2026, 10, 15)));
		MostActiveDays mostActiveDays = new MostActiveDays(dates, 1L);

		dates.add(LocalDate.of(2026, 10, 16));

		assertThat(mostActiveDays.dates()).containsExactly(LocalDate.of(2026, 10, 15));
	}

}
