package com.beautica.master.service;

import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.repository.ScheduleTemplateIntervalRow;
import com.beautica.master.repository.ScheduleTemplateTimeRow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds {@link ScheduleTemplate}s from the batched projection
 * ({@code WeeklyScheduleRepository#findTemplateIntervalRowsByMasterIds}) — B523 perf fix.
 *
 * <p><b>Same order, same answers as the entity path.</b> Templates keep the query's
 * {@code master, valid_from DESC, id} order per master (the fold's first-covering rule depends on it);
 * intervals are sorted by start time and discrete times sorted + de-duplicated exactly as
 * {@link ScheduleMapper#toIntervalDtosForDay} / {@link ScheduleMapper#toDiscreteTimesForDay} do.
 *
 * <p><b>Discrete times load lazily, once per batch.</b> The first template asked for its discrete
 * times loads EVERY template's times of the batch in one statement — the same one-statement shape the
 * entity path's {@code WeeklySchedule.discreteTimes} batch fetch had, and still only when a fold
 * actually reaches a template day. Request-scoped and single-threaded like the fold itself.
 */
final class ProjectedScheduleTemplates {

    private static final Comparator<WorkIntervalDto> BY_START =
            Comparator.comparing(WorkIntervalDto::startTime);

    private ProjectedScheduleTemplates() {
    }

    /**
     * Groups {@code rows} into templates per master, in row order. {@code timeLoader} is called at most
     * once, with the distinct schedule ids of the whole batch, the first time any template's discrete
     * times are read.
     */
    static Map<UUID, List<ScheduleTemplate>> byMaster(
            List<ScheduleTemplateIntervalRow> rows,
            Function<UUID[], List<ScheduleTemplateTimeRow>> timeLoader) {
        Map<UUID, Projected> byId = new LinkedHashMap<>();
        Map<UUID, List<ScheduleTemplate>> byMaster = new LinkedHashMap<>();
        DiscreteTimes times = new DiscreteTimes(byId, timeLoader);
        for (ScheduleTemplateIntervalRow row : rows) {
            Projected template = byId.computeIfAbsent(row.getScheduleId(), id -> {
                Projected created = new Projected(id, row.getValidFrom(), row.getValidTo(), times);
                byMaster.computeIfAbsent(row.getMasterId(), m -> new ArrayList<>()).add(created);
                return created;
            });
            if (row.getDayOfWeek() != null) {
                template.addInterval(row.getDayOfWeek(), new WorkIntervalDto(row.getStartTime(), row.getEndTime()));
            }
        }
        byId.values().forEach(Projected::sortIntervals);
        return byMaster;
    }

    /** One projected template; immutable once {@link #byMaster} returns. */
    private static final class Projected implements ScheduleTemplate {

        private final UUID scheduleId;
        private final LocalDate validFrom;
        private final LocalDate validTo;
        private final DiscreteTimes times;
        private final Map<Integer, List<WorkIntervalDto>> intervalsByDow = new HashMap<>();

        private Projected(UUID scheduleId, LocalDate validFrom, LocalDate validTo, DiscreteTimes times) {
            this.scheduleId = scheduleId;
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.times = times;
        }

        private void addInterval(int isoDow, WorkIntervalDto interval) {
            intervalsByDow.computeIfAbsent(isoDow, d -> new ArrayList<>()).add(interval);
        }

        private void sortIntervals() {
            intervalsByDow.values().forEach(list -> list.sort(BY_START));
        }

        @Override
        public LocalDate validFrom() {
            return validFrom;
        }

        @Override
        public LocalDate validTo() {
            return validTo;
        }

        @Override
        public List<WorkIntervalDto> intervalsForDay(int isoDow) {
            return new ArrayList<>(intervalsByDow.getOrDefault(isoDow, List.of()));
        }

        @Override
        public List<LocalTime> discreteTimesForDay(int isoDow) {
            return new ArrayList<>(times.of(scheduleId).getOrDefault(isoDow, List.of()));
        }
    }

    /** The batch's discrete times, loaded on first read: scheduleId -> isoDow -> sorted distinct times. */
    private static final class DiscreteTimes {

        private final Map<UUID, Projected> templates;
        private final Function<UUID[], List<ScheduleTemplateTimeRow>> loader;
        private Map<UUID, Map<Integer, List<LocalTime>>> loaded;

        private DiscreteTimes(Map<UUID, Projected> templates,
                Function<UUID[], List<ScheduleTemplateTimeRow>> loader) {
            this.templates = templates;
            this.loader = loader;
        }

        private Map<Integer, List<LocalTime>> of(UUID scheduleId) {
            if (loaded == null) {
                loaded = load();
            }
            return loaded.getOrDefault(scheduleId, Map.of());
        }

        private Map<UUID, Map<Integer, List<LocalTime>>> load() {
            Map<UUID, Map<Integer, List<LocalTime>>> result = new HashMap<>();
            for (ScheduleTemplateTimeRow row : loader.apply(templates.keySet().toArray(UUID[]::new))) {
                result.computeIfAbsent(row.getScheduleId(), id -> new HashMap<>())
                        .computeIfAbsent(row.getDayOfWeek(), d -> new ArrayList<>())
                        .add(row.getSlotTime());
            }
            result.values().forEach(byDow -> byDow.replaceAll((dow, list) ->
                    list.stream().sorted().distinct().toList()));
            return result;
        }
    }
}
