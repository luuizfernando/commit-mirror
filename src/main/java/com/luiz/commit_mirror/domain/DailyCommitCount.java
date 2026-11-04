package com.luiz.commit_mirror.domain;

import java.time.LocalDate;

public record DailyCommitCount(LocalDate date, long commits) {
}
