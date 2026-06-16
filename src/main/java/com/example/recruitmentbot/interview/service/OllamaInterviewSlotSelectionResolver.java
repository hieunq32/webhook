package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.interview.config.InterviewSchedulingProperties;
import com.example.recruitmentbot.interview.domain.InterviewSlot;
import com.example.recruitmentbot.service.OllamaService;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OllamaInterviewSlotSelectionResolver implements InterviewSlotSelectionResolver {

    private static final Logger log = LoggerFactory.getLogger(OllamaInterviewSlotSelectionResolver.class);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Saigon");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d{1,2})\\b");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final OllamaService ollamaService;
    private final InterviewSchedulingProperties properties;

    public OllamaInterviewSlotSelectionResolver(OllamaService ollamaService, InterviewSchedulingProperties properties) {
        this.ollamaService = ollamaService;
        this.properties = properties;
    }

    @Override
    public Resolution resolve(String candidateMessage, List<InterviewSlot> offeredSlots) {
        Resolution heuristicResolution = resolveHeuristically(candidateMessage, offeredSlots);
        if (heuristicResolution.type() != ResolutionType.NO_MATCH
                && heuristicResolution.type() != ResolutionType.AMBIGUOUS) {
            return heuristicResolution;
        }

        try {
            String prompt = buildPrompt(candidateMessage, offeredSlots);
            String aiReply = ollamaService.generate(
                    "You map candidate interview slot replies to a strict token output.",
                    prompt
            );
            Resolution aiResolution = parseAiResolution(aiReply);
            if (aiResolution != null) {
                return aiResolution;
            }
        } catch (Exception exception) {
            log.warn("Falling back from Ollama slot selection resolver to heuristic result", exception);
        }
        return heuristicResolution;
    }

    private Resolution resolveHeuristically(String candidateMessage, List<InterviewSlot> offeredSlots) {
        String normalized = normalize(candidateMessage);
        if (!StringUtils.hasText(normalized)) {
            return new Resolution(ResolutionType.NO_MATCH, null);
        }
        if (containsAny(normalized, "doi lich", "doi gio", "reschedule", "another slot", "another time", "slot khac")) {
            return new Resolution(ResolutionType.RESCHEDULE, null);
        }
        if (containsAny(normalized, "khong chac", "phan van", "khong biet", "khong ro", "sure", "not sure", "help me")) {
            return new Resolution(ResolutionType.UNSURE, null);
        }

        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        while (matcher.find()) {
            int option = Integer.parseInt(matcher.group(1));
            if (option >= 1 && option <= offeredSlots.size()) {
                return new Resolution(ResolutionType.SELECTED, option);
            }
        }

        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < offeredSlots.size(); index++) {
            InterviewSlot slot = offeredSlots.get(index);
            OffsetDateTime localStart = slot.getStartTime().atZoneSameInstant(DISPLAY_ZONE).toOffsetDateTime();
            String dayName = normalize(localStart.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
            String timeText = localStart.format(TIME_FORMATTER);
            String hourText = String.valueOf(localStart.getHour());
            String dayOfMonthText = String.valueOf(localStart.getDayOfMonth());
            boolean dayMatch = normalized.contains(dayName) || normalized.contains(dayOfMonthText + "/");
            boolean timeMatch = normalized.contains(timeText) || normalized.contains(hourText + "h");
            if (dayMatch && timeMatch) {
                matches.add(index + 1);
            } else if (dayMatch && matches.isEmpty()) {
                matches.add(index + 1);
            } else if (timeMatch) {
                matches.add(index + 1);
            }
        }

        if (matches.size() == 1) {
            return new Resolution(ResolutionType.SELECTED, matches.get(0));
        }
        if (matches.size() > 1) {
            return new Resolution(ResolutionType.AMBIGUOUS, null);
        }
        return new Resolution(ResolutionType.NO_MATCH, null);
    }

    private String buildPrompt(String candidateMessage, List<InterviewSlot> offeredSlots) {
        StringBuilder options = new StringBuilder();
        for (int index = 0; index < offeredSlots.size(); index++) {
            InterviewSlot slot = offeredSlots.get(index);
            options.append(index + 1)
                    .append(". ")
                    .append(slot.getStartTime().getDayOfWeek())
                    .append(" ")
                    .append(slot.getStartTime().toLocalDate())
                    .append(" ")
                    .append(slot.getStartTime().toLocalTime())
                    .append(" - ")
                    .append(slot.getEndTime().toLocalTime())
                    .append('\n');
        }
        return properties.slotSelectionPromptTemplate()
                .replace("{{options}}", options.toString().trim())
                .replace("{{reply}}", candidateMessage == null ? "" : candidateMessage);
    }

    private Resolution parseAiResolution(String aiReply) {
        String normalized = normalize(aiReply);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (normalized.startsWith("select")) {
            Matcher matcher = NUMBER_PATTERN.matcher(normalized);
            if (matcher.find()) {
                return new Resolution(ResolutionType.SELECTED, Integer.parseInt(matcher.group(1)));
            }
        }
        if (normalized.contains("reschedule")) {
            return new Resolution(ResolutionType.RESCHEDULE, null);
        }
        if (normalized.contains("unsure")) {
            return new Resolution(ResolutionType.UNSURE, null);
        }
        if (normalized.contains("ambiguous")) {
            return new Resolution(ResolutionType.AMBIGUOUS, null);
        }
        if (normalized.contains("no match")) {
            return new Resolution(ResolutionType.NO_MATCH, null);
        }
        return null;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9:\\s/]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }
}
