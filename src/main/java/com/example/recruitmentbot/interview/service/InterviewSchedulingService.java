package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.interview.config.InterviewSchedulingProperties;
import com.example.recruitmentbot.interview.domain.CandidateProfile;
import com.example.recruitmentbot.interview.domain.HrInterviewNotification;
import com.example.recruitmentbot.interview.domain.HrInterviewNotificationStatus;
import com.example.recruitmentbot.interview.domain.InterviewConversation;
import com.example.recruitmentbot.interview.domain.InterviewConversationState;
import com.example.recruitmentbot.interview.domain.InterviewSlot;
import com.example.recruitmentbot.interview.domain.InterviewSlotStatus;
import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartRequest;
import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartResponse;
import com.example.recruitmentbot.interview.dto.InterviewSlotOptionResponse;
import com.example.recruitmentbot.interview.repository.HrInterviewNotificationRepository;
import com.example.recruitmentbot.interview.repository.InterviewConversationRepository;
import com.example.recruitmentbot.interview.repository.InterviewSlotRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class InterviewSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSchedulingService.class);
    private static final DateTimeFormatter SLOT_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.ENGLISH);
    private static final EnumSet<InterviewConversationState> ACTIVE_CANDIDATE_STATES =
            EnumSet.of(
                    InterviewConversationState.AWAITING_SLOT_SELECTION,
                    InterviewConversationState.HR_PENDING,
                    InterviewConversationState.RESCHEDULE_REQUESTED
            );

    private final InterviewConversationRepository conversationRepository;
    private final InterviewSlotRepository slotRepository;
    private final HrInterviewNotificationRepository notificationRepository;
    private final VOfficeAvailabilityService vofficeAvailabilityService;
    private final ChannelMessagingService channelMessagingService;
    private final InterviewSlotSelectionResolver slotSelectionResolver;
    private final InterviewSchedulingProperties properties;
    private final CandidateProfileService candidateProfileService;

    public InterviewSchedulingService(
            InterviewConversationRepository conversationRepository,
            InterviewSlotRepository slotRepository,
            HrInterviewNotificationRepository notificationRepository,
            VOfficeAvailabilityService vofficeAvailabilityService,
            ChannelMessagingService channelMessagingService,
            InterviewSlotSelectionResolver slotSelectionResolver,
            InterviewSchedulingProperties properties,
            CandidateProfileService candidateProfileService
    ) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.notificationRepository = notificationRepository;
        this.vofficeAvailabilityService = vofficeAvailabilityService;
        this.channelMessagingService = channelMessagingService;
        this.slotSelectionResolver = slotSelectionResolver;
        this.properties = properties;
        this.candidateProfileService = candidateProfileService;
    }

    @Transactional
    public InterviewSchedulingStartResponse startScheduling(InterviewSchedulingStartRequest request) {
        ensureEnabled();
        CandidateProfile candidateProfile = candidateProfileService.upsertFromInterviewStartRequest(request);
        if (!candidateProfile.isPassCv()) {
            throw new IllegalStateException("Candidate has not passed CV screening yet");
        }
        conversationRepository.findFirstByCandidateSenderIdAndStateInOrderByUpdatedAtDesc(
                request.candidateSenderId(),
                ACTIVE_CANDIDATE_STATES
        ).ifPresent(this::cancelAndReleaseConversation);

        InterviewConversation conversation = new InterviewConversation();
        conversation.setCandidateSenderId(candidateProfile.getSenderId());
        conversation.setCandidateName(candidateProfile.getCandidateName());
        conversation.setAppliedPosition(candidateProfile.getAppliedPosition());
        conversation.setDepartment(candidateProfile.getDepartment());
        conversation.setCvScore(candidateProfile.getCvScore());
        conversation.setState(InterviewConversationState.AWAITING_SLOT_SELECTION);
        conversation = conversationRepository.save(conversation);

        List<InterviewSlot> offeredSlots = reserveFreshSlots(conversation, conversation.getDepartment());
        channelMessagingService.sendText(
                request.candidateSenderId(),
                buildCandidateOfferMessage(conversation, offeredSlots, true, false)
        );
        return toStartResponse(conversation, offeredSlots);
    }

    @Transactional
    public boolean autoStartSchedulingForPassedCandidateIfNeeded(String senderId) {
        if (!properties.enabled()) {
            return false;
        }
        CandidateProfile candidateProfile = candidateProfileService.getPassedCandidateBySenderId(senderId);
        if (candidateProfile == null) {
            return false;
        }

        Optional<InterviewConversation> activeConversation = conversationRepository
                .findFirstByCandidateSenderIdAndStateInOrderByUpdatedAtDesc(senderId, ACTIVE_CANDIDATE_STATES);
        if (activeConversation.isPresent()) {
            return false;
        }
        if (conversationRepository.findFirstByCandidateSenderIdOrderByUpdatedAtDesc(senderId).isPresent()) {
            return false;
        }

        InterviewConversation conversation = new InterviewConversation();
        conversation.setCandidateSenderId(candidateProfile.getSenderId());
        conversation.setCandidateName(candidateProfile.getCandidateName());
        conversation.setAppliedPosition(candidateProfile.getAppliedPosition());
        conversation.setDepartment(candidateProfile.getDepartment());
        conversation.setCvScore(candidateProfile.getCvScore());
        conversation.setState(InterviewConversationState.AWAITING_SLOT_SELECTION);
        conversation = conversationRepository.save(conversation);

        List<InterviewSlot> offeredSlots = reserveFreshSlots(conversation, candidateProfile.getDepartment());
        channelMessagingService.sendText(
                candidateProfile.getSenderId(),
                buildCandidateOfferMessage(conversation, offeredSlots, true, false)
        );
        return true;
    }

    @Transactional
    public boolean handleCandidateReplyIfApplicable(String senderId, String messageText) {
        if (!properties.enabled()) {
            return false;
        }
        Optional<InterviewConversation> conversationOptional =
                conversationRepository.findFirstByCandidateSenderIdAndStateInOrderByUpdatedAtDesc(senderId, ACTIVE_CANDIDATE_STATES);
        if (conversationOptional.isEmpty()) {
            return false;
        }

        InterviewConversation conversation = conversationOptional.get();
        if (conversation.getState() == InterviewConversationState.HR_PENDING) {
            if (isRescheduleRequest(messageText)) {
                releaseBookedSlot(conversation);
                reOfferSlots(conversation, true);
            } else {
                channelMessagingService.sendText(senderId,
                        "Mình đã giữ lịch bạn chọn và đang chờ HR xác nhận. Nếu bạn muốn đổi lịch, hãy nhắn 'đổi lịch'.");
            }
            return true;
        }

        if (isSelectionExpired(conversation)) {
            reOfferSlots(conversation, false);
            return true;
        }

        List<InterviewSlot> offeredSlots = getPresentedSlots(conversation);
        if (offeredSlots.isEmpty()) {
            reOfferSlots(conversation, false);
            return true;
        }

        InterviewSlotSelectionResolver.Resolution resolution = slotSelectionResolver.resolve(messageText, offeredSlots);
        switch (resolution.type()) {
            case SELECTED -> confirmCandidateSlotSelection(conversation, offeredSlots, resolution.optionNumber());
            case RESCHEDULE, UNSURE -> {
                reOfferSlots(conversation, true);
            }
            case AMBIGUOUS -> {
                channelMessagingService.sendText(
                        senderId,
                        "Mình chưa chắc bạn muốn chọn khung nào. Bạn có thể trả lời lại bằng số, thứ/ngày hoặc giờ từ danh sách bên dưới:\n\n"
                                + formatSlotOptions(offeredSlots)
                );
            }
            case NO_MATCH -> {
                channelMessagingService.sendText(
                        senderId,
                        "Mình chưa map được câu trả lời đó vào lịch phỏng vấn hiện có. Bạn có thể trả lời bằng số, ví dụ '1', hoặc nêu rõ thứ/ngày và giờ.\n\n"
                                + formatSlotOptions(offeredSlots)
                );
            }
        }
        return true;
    }

    @Transactional
    public boolean handleHrReplyIfApplicable(String senderId, String messageText) {
        if (!properties.enabled() || !StringUtils.hasText(senderId)) {
            return false;
        }

        HrAction action = parseHrAction(messageText);
        if (action == HrAction.NONE) {
            return false;
        }

        Long conversationId = parseConversationReference(messageText);
        List<HrInterviewNotification> pendingNotifications =
                notificationRepository.findAllByHrRecipientIdAndStatusOrderBySentAtDesc(
                        senderId,
                        HrInterviewNotificationStatus.PENDING
                );
        HrInterviewNotification notification = resolvePendingNotification(pendingNotifications, conversationId);
        if (notification == null) {
            channelMessagingService.sendText(senderId,
                    "Không tìm thấy yêu cầu phỏng vấn đang chờ xử lý. Nếu có nhiều lịch chờ, hãy trả lời theo mẫu: XAC NHAN <ma> hoặc DOI LICH <ma>.");
            return true;
        }

        InterviewConversation conversation = conversationRepository.findById(notification.getConversationId())
                .orElseThrow(() -> new IllegalStateException("Interview conversation not found: " + notification.getConversationId()));

        if (action == HrAction.CONFIRM) {
            confirmByHr(conversation, notification);
        } else {
            requestReschedule(conversation, notification);
        }
        return true;
    }

    @Transactional
    public void releaseExpiredSoftLocks() {
        if (!properties.enabled()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<InterviewConversation> activeConversations = conversationRepository.findAllByStateIn(
                List.of(InterviewConversationState.AWAITING_SLOT_SELECTION, InterviewConversationState.RESCHEDULE_REQUESTED)
        );
        for (InterviewConversation conversation : activeConversations) {
            if (conversation.getSelectionLockExpiresAt() != null && conversation.getSelectionLockExpiresAt().isBefore(now)) {
                releasePresentedSlots(conversation);
                conversation.setLastPresentedSlotIds(null);
                conversation.setSelectionLockExpiresAt(null);
            }
        }
    }

    @Transactional
    public void autoConfirmExpiredHrNotifications() {
        if (!properties.enabled()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<HrInterviewNotification> pendingNotifications =
                notificationRepository.findAllByStatusAndResponseDeadlineAtBefore(HrInterviewNotificationStatus.PENDING, now);
        for (HrInterviewNotification notification : pendingNotifications) {
            InterviewConversation conversation = conversationRepository.findById(notification.getConversationId())
                    .orElse(null);
            if (conversation == null || conversation.getState() != InterviewConversationState.HR_PENDING) {
                continue;
            }
            notification.setStatus(HrInterviewNotificationStatus.AUTO_CONFIRMED);
            notification.setRespondedAt(now);
            conversation.setState(InterviewConversationState.CONFIRMED);
            conversation.setHrDecisionDeadlineAt(null);
            channelMessagingService.sendText(
                    conversation.getCandidateSenderId(),
                    buildFinalCandidateConfirmation(conversation)
            );
            channelMessagingService.sendText(
                    notification.getHrRecipientId(),
                    "Hệ thống đã tự xác nhận lịch phỏng vấn cho mã " + conversation.getId()
                            + " do quá thời gian phản hồi."
            );
        }
    }

    @Transactional
    public String buildUpcomingVOfficeScheduleSummary() {
        ensureEnabled();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime toTime = now.plusDays(8);
        syncSlotsFromVOffice(properties.defaultDepartment());

        List<InterviewSlot> slots = slotRepository.findAllByStartTimeBetweenOrderByStartTimeAsc(now, toTime);
        if (slots.isEmpty()) {
            return "Lịch VOffice tuần tới hiện chưa có khung giờ nào.";
        }

        StringBuilder builder = new StringBuilder("Lịch VOffice tuần tới:\n");
        int count = 0;
        for (InterviewSlot slot : slots) {
            if (slot.isExpiredSoftLock(now)) {
                slot.release();
            }
            count++;
            builder.append(count)
                    .append(". ")
                    .append(formatSlotLabel(slot))
                    .append(" | ")
                    .append(mapSlotStatus(slot))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Interview scheduling feature is disabled");
        }
    }

    private String resolveDepartment(String requestedDepartment) {
        return StringUtils.hasText(requestedDepartment) ? requestedDepartment.trim() : properties.defaultDepartment();
    }

    private void cancelAndReleaseConversation(InterviewConversation conversation) {
        releasePresentedSlots(conversation);
        releaseBookedSlot(conversation);
        conversation.setState(InterviewConversationState.CANCELLED);
        conversation.setLastPresentedSlotIds(null);
        conversation.setSelectionLockExpiresAt(null);
        conversation.setHrDecisionDeadlineAt(null);
    }

    private List<InterviewSlot> reserveFreshSlots(InterviewConversation conversation, String department) {
        releasePresentedSlots(conversation);
        syncSlotsFromVOffice(department);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime toTime = now.plusDays(8);
        List<InterviewSlot> upcomingSlots = slotRepository.findUpcomingForUpdate(now, toTime);
        List<InterviewSlot> availableSlots = new ArrayList<>();

        for (InterviewSlot slot : upcomingSlots) {
            if (slot.isExpiredSoftLock(now)) {
                slot.release();
            }
            if (slot.getStatus() == InterviewSlotStatus.AVAILABLE) {
                availableSlots.add(slot);
            }
            if (availableSlots.size() >= properties.slotOfferCount()) {
                break;
            }
        }

        if (availableSlots.isEmpty()) {
            throw new IllegalStateException("No interview slots are available for the upcoming week");
        }

        OffsetDateTime lockExpiry = now.plusMinutes(properties.softLockMinutes());
        for (InterviewSlot slot : availableSlots) {
            slot.softLock(conversation.getId(), lockExpiry);
        }

        conversation.setState(InterviewConversationState.AWAITING_SLOT_SELECTION);
        conversation.setSelectedSlotId(null);
        conversation.setHrDecisionDeadlineAt(null);
        conversation.setSelectionLockExpiresAt(lockExpiry);
        conversation.setLastPresentedSlotIds(
                availableSlots.stream()
                        .map(slot -> String.valueOf(slot.getId()))
                        .collect(Collectors.joining(","))
        );
        return availableSlots;
    }

    private void syncSlotsFromVOffice(String department) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime toTime = now.plusDays(8);
        for (VOfficeAvailabilityService.VOfficeSlot vofficeSlot :
                vofficeAvailabilityService.getAvailableSlots(department, now, toTime)) {
            InterviewSlot slot = slotRepository.findBySlotKey(vofficeSlot.slotKey())
                    .orElseGet(InterviewSlot::new);
            slot.setSlotKey(vofficeSlot.slotKey());
            slot.setDepartment(vofficeSlot.department());
            slot.setStartTime(vofficeSlot.startTime());
            slot.setEndTime(vofficeSlot.endTime());
            if (slot.getStatus() == null) {
                slot.setStatus(InterviewSlotStatus.AVAILABLE);
            }
            slotRepository.save(slot);
        }
    }

    private void confirmCandidateSlotSelection(
            InterviewConversation conversation,
            List<InterviewSlot> offeredSlots,
            Integer optionNumber
    ) {
        if (optionNumber == null || optionNumber < 1 || optionNumber > offeredSlots.size()) {
            channelMessagingService.sendText(
                    conversation.getCandidateSenderId(),
                    "Mình chưa xác định được lựa chọn hợp lệ. Bạn hãy thử lại bằng số hoặc nêu rõ ngày giờ."
            );
            return;
        }

        InterviewSlot chosen = offeredSlots.get(optionNumber - 1);
        InterviewSlot lockedSlot = slotRepository.findByIdForUpdate(chosen.getId())
                .orElseThrow(() -> new IllegalStateException("Interview slot not found: " + chosen.getId()));
        if (lockedSlot.getStatus() != InterviewSlotStatus.SOFT_LOCKED
                || !conversation.getId().equals(lockedSlot.getLockedByConversationId())) {
            reOfferSlots(conversation, false);
            return;
        }

        lockedSlot.book(conversation.getId());
        releaseOtherPresentedSlots(conversation, lockedSlot.getId());
        conversation.setSelectedSlotId(lockedSlot.getId());
        conversation.setSelectionLockExpiresAt(null);
        conversation.setState(InterviewConversationState.HR_PENDING);
        conversation.setHrDecisionDeadlineAt(OffsetDateTime.now().plusMinutes(properties.hrResponseTimeoutMinutes()));

        HrInterviewNotification notification = new HrInterviewNotification();
        notification.setConversationId(conversation.getId());
        notification.setHrRecipientId(resolveHrRecipientId(conversation.getCandidateSenderId()));
        notification.setInterviewSlotId(lockedSlot.getId());
        notification.setStatus(HrInterviewNotificationStatus.PENDING);
        notification.setSentAt(OffsetDateTime.now());
        notification.setResponseDeadlineAt(conversation.getHrDecisionDeadlineAt());
        notification.setMessageBody(buildHrNotificationMessage(conversation, lockedSlot));
        notificationRepository.save(notification);

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Mình đã giữ lịch " + formatSlotLabel(lockedSlot)
                        + " cho bạn và đang chờ HR xác nhận. Nếu bạn muốn đổi lịch trước khi HR phản hồi, hãy nhắn 'đổi lịch'."
        );
        channelMessagingService.sendText(notification.getHrRecipientId(), notification.getMessageBody());
    }

    private void confirmByHr(InterviewConversation conversation, HrInterviewNotification notification) {
        conversation.setState(InterviewConversationState.CONFIRMED);
        conversation.setHrDecisionDeadlineAt(null);
        notification.setStatus(HrInterviewNotificationStatus.CONFIRMED);
        notification.setRespondedAt(OffsetDateTime.now());

        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã xác nhận lịch phỏng vấn cho mã " + conversation.getId() + "."
        );
        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                buildFinalCandidateConfirmation(conversation)
        );
    }

    private void requestReschedule(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.RESCHEDULE_REQUESTED);
        notification.setRespondedAt(OffsetDateTime.now());
        releaseBookedSlot(conversation);
        conversation.setState(InterviewConversationState.RESCHEDULE_REQUESTED);
        conversation.setSelectedSlotId(null);
        conversation.setHrDecisionDeadlineAt(null);
        reOfferSlots(conversation, true);
        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã yêu cầu ứng viên chọn lại lịch cho mã " + conversation.getId() + "."
        );
    }

    private void reOfferSlots(InterviewConversation conversation, boolean apologizeFirst) {
        List<InterviewSlot> offeredSlots = reserveFreshSlots(conversation, conversation.getDepartment());
        String message = buildCandidateOfferMessage(conversation, offeredSlots, false, apologizeFirst);
        channelMessagingService.sendText(conversation.getCandidateSenderId(), message);
    }

    private void releasePresentedSlots(InterviewConversation conversation) {
        List<InterviewSlot> lockedSlots = slotRepository.findAllByLockedByConversationId(conversation.getId());
        for (InterviewSlot slot : lockedSlots) {
            if (slot.getStatus() == InterviewSlotStatus.SOFT_LOCKED) {
                slot.release();
            }
        }
    }

    private void releaseOtherPresentedSlots(InterviewConversation conversation, Long selectedSlotId) {
        for (InterviewSlot slot : getPresentedSlots(conversation)) {
            if (!slot.getId().equals(selectedSlotId) && slot.getStatus() == InterviewSlotStatus.SOFT_LOCKED) {
                slot.release();
            }
        }
    }

    private void releaseBookedSlot(InterviewConversation conversation) {
        if (conversation.getSelectedSlotId() == null) {
            return;
        }
        slotRepository.findByIdForUpdate(conversation.getSelectedSlotId()).ifPresent(slot -> {
            if (slot.getStatus() == InterviewSlotStatus.BOOKED
                    && conversation.getId().equals(slot.getBookedByConversationId())) {
                slot.setBookedByConversationId(null);
                slot.setBookedAt(null);
                slot.release();
            }
        });
    }

    private List<InterviewSlot> getPresentedSlots(InterviewConversation conversation) {
        if (!StringUtils.hasText(conversation.getLastPresentedSlotIds())) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String token : conversation.getLastPresentedSlotIds().split(",")) {
            if (StringUtils.hasText(token)) {
                ids.add(Long.parseLong(token.trim()));
            }
        }
        return slotRepository.findAllByIdIn(ids).stream()
                .sorted(Comparator.comparing(InterviewSlot::getStartTime))
                .toList();
    }

    private boolean isSelectionExpired(InterviewConversation conversation) {
        return conversation.getSelectionLockExpiresAt() != null
                && conversation.getSelectionLockExpiresAt().isBefore(OffsetDateTime.now());
    }

    private boolean isRescheduleRequest(String text) {
        String normalized = normalize(text);
        return normalized.contains("doi lich")
                || normalized.contains("doi gio")
                || normalized.contains("reschedule")
                || normalized.contains("slot khac");
    }

    private String buildCandidateOfferMessage(
            InterviewConversation conversation,
            List<InterviewSlot> offeredSlots,
            boolean firstOffer,
            boolean apology
    ) {
        StringBuilder builder = new StringBuilder();
        if (firstOffer) {
            builder.append("Chúc mừng ").append(conversation.getCandidateName())
                    .append(", hồ sơ của bạn đã qua vòng CV cho vị trí ")
                    .append(conversation.getAppliedPosition())
                    .append(" (điểm CV: ").append(conversation.getCvScore()).append(").");
        } else if (apology) {
            builder.append("Xin lỗi, HR cần bạn chọn lại lịch phỏng vấn.");
        } else {
            builder.append("Các lựa chọn trước đã hết hạn hoặc chưa đủ rõ. Mình gửi lại các khung giờ còn trống.");
        }
        builder.append("\n\nCác khung giờ phỏng vấn còn trống trong tuần tới:\n");
        builder.append(formatSlotOptions(offeredSlots));
        builder.append("\n\nBạn có thể trả lời bằng số, ngày, giờ hoặc kết hợp. Ví dụ: '1', 'thu 3 14h', '09:00 sang thu 5'.");
        return builder.toString();
    }

    private String formatSlotOptions(List<InterviewSlot> offeredSlots) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < offeredSlots.size(); index++) {
            InterviewSlot slot = offeredSlots.get(index);
            builder.append(index + 1)
                    .append(". ")
                    .append(formatSlotLabel(slot))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private String formatSlotLabel(InterviewSlot slot) {
        return slot.getStartTime().format(SLOT_LABEL_FORMATTER)
                + " - "
                + slot.getEndTime().toLocalTime();
    }

    private String mapSlotStatus(InterviewSlot slot) {
        return switch (slot.getStatus()) {
            case AVAILABLE -> "Con trong";
            case SOFT_LOCKED -> "Dang tam giu";
            case BOOKED -> "Da dat";
        };
    }

    private String buildHrNotificationMessage(InterviewConversation conversation, InterviewSlot slot) {
        return "Yeu cau xac nhan lich phong van\n"
                + "Ma: " + conversation.getId() + "\n"
                + "Ung vien: " + conversation.getCandidateName() + "\n"
                + "Vi tri: " + conversation.getAppliedPosition() + "\n"
                + "Diem CV: " + conversation.getCvScore() + "\n"
                + "Khung gio da chon: " + formatSlotLabel(slot) + "\n"
                + "Tra loi: XAC NHAN " + conversation.getId() + " hoac DOI LICH " + conversation.getId() + "\n"
                + "Neu qua " + properties.hrResponseTimeoutMinutes() + " phut khong phan hoi, he thong se tu xac nhan.";
    }

    private String buildFinalCandidateConfirmation(InterviewConversation conversation) {
        InterviewSlot slot = slotRepository.findById(conversation.getSelectedSlotId())
                .orElseThrow(() -> new IllegalStateException("Selected interview slot not found: " + conversation.getSelectedSlotId()));
        return "Lịch phỏng vấn của bạn đã được xác nhận.\n"
                + "Thời gian: " + formatSlotLabel(slot) + "\n"
                + "Địa điểm: " + properties.interviewLocation() + "\n"
                + "Chuẩn bị: " + properties.preparationNotes();
    }

    private String resolveHrRecipientId(String candidateSenderId) {
        CandidateProfile candidateProfile = candidateProfileService.getPassedCandidateBySenderId(candidateSenderId);
        if (candidateProfile != null && StringUtils.hasText(candidateProfile.getAssignedHrSenderId())) {
            return candidateProfile.getAssignedHrSenderId();
        }
        return properties.hrRecipientId();
    }

    private InterviewSchedulingStartResponse toStartResponse(
            InterviewConversation conversation,
            List<InterviewSlot> offeredSlots
    ) {
        List<InterviewSlotOptionResponse> options = new ArrayList<>();
        for (int index = 0; index < offeredSlots.size(); index++) {
            InterviewSlot slot = offeredSlots.get(index);
            options.add(new InterviewSlotOptionResponse(
                    slot.getId(),
                    index + 1,
                    formatSlotLabel(slot),
                    slot.getStartTime().toString(),
                    slot.getEndTime().toString()
            ));
        }
        return new InterviewSchedulingStartResponse(
                conversation.getId(),
                conversation.getState().name(),
                conversation.getCandidateSenderId(),
                conversation.getCandidateName(),
                options
        );
    }

    private HrInterviewNotification resolvePendingNotification(
            List<HrInterviewNotification> pendingNotifications,
            Long conversationId
    ) {
        if (conversationId != null) {
            for (HrInterviewNotification notification : pendingNotifications) {
                if (conversationId.equals(notification.getConversationId())) {
                    return notification;
                }
            }
            return null;
        }
        return pendingNotifications.size() == 1 ? pendingNotifications.get(0) : null;
    }

    private HrAction parseHrAction(String messageText) {
        String normalized = normalize(messageText);
        if (normalized.contains("xac nhan") || normalized.contains("confirm")) {
            return HrAction.CONFIRM;
        }
        if (normalized.contains("doi lich") || normalized.contains("reschedule")) {
            return HrAction.RESCHEDULE;
        }
        return HrAction.NONE;
    }

    private Long parseConversationReference(String messageText) {
        if (!StringUtils.hasText(messageText)) {
            return null;
        }
        String normalized = normalize(messageText);
        String[] tokens = normalized.split(" ");
        for (String token : tokens) {
            if (token.matches("\\d+")) {
                return Long.parseLong(token);
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private enum HrAction {
        CONFIRM,
        RESCHEDULE,
        NONE
    }
}
