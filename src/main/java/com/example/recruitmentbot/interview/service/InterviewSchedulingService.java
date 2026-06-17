package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.council.domain.RecruitmentCouncil;
import com.example.recruitmentbot.council.service.RecruitmentCouncilService;
import com.example.recruitmentbot.interview.config.InterviewSchedulingProperties;
import com.example.recruitmentbot.interview.domain.CandidateProfile;
import com.example.recruitmentbot.interview.domain.HrInterviewNotificationKind;
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
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.repository.JobDescriptionRepository;
import com.example.recruitmentbot.hradmin.service.PageAdminAccountService;
import jakarta.transaction.Transactional;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Saigon");
    private static final DateTimeFormatter SLOT_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.ENGLISH);
    private static final EnumSet<InterviewConversationState> ACTIVE_CANDIDATE_STATES =
            EnumSet.of(
                    InterviewConversationState.AWAITING_SLOT_SELECTION,
                    InterviewConversationState.HR_PENDING,
                    InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT,
                    InterviewConversationState.AWAITING_RESCHEDULE_REASON,
                    InterviewConversationState.HR_RESCHEDULE_REVIEW,
                    InterviewConversationState.COUNCIL_PENDING,
                    InterviewConversationState.CANDIDATE_REVIEWING_COUNCIL_SLOT,
                    InterviewConversationState.RESCHEDULE_REQUESTED
            );
    private static final EnumSet<InterviewConversationState> RESCHEDULABLE_CANDIDATE_STATES =
            EnumSet.of(
                    InterviewConversationState.CONFIRMED,
                    InterviewConversationState.HR_PENDING,
                    InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT,
                    InterviewConversationState.COUNCIL_PENDING,
                    InterviewConversationState.CANDIDATE_REVIEWING_COUNCIL_SLOT,
                    InterviewConversationState.COUNCIL_REJECTED
            );

    private final InterviewConversationRepository conversationRepository;
    private final InterviewSlotRepository slotRepository;
    private final HrInterviewNotificationRepository notificationRepository;
    private final VOfficeAvailabilityService vofficeAvailabilityService;
    private final ChannelMessagingService channelMessagingService;
    private final InterviewSlotSelectionResolver slotSelectionResolver;
    private final InterviewSchedulingProperties properties;
    private final CandidateProfileService candidateProfileService;
    private final RecruitmentCouncilService recruitmentCouncilService;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final PageAdminAccountService pageAdminAccountService;

    public InterviewSchedulingService(
            InterviewConversationRepository conversationRepository,
            InterviewSlotRepository slotRepository,
            HrInterviewNotificationRepository notificationRepository,
            VOfficeAvailabilityService vofficeAvailabilityService,
            ChannelMessagingService channelMessagingService,
            InterviewSlotSelectionResolver slotSelectionResolver,
            InterviewSchedulingProperties properties,
            CandidateProfileService candidateProfileService,
            RecruitmentCouncilService recruitmentCouncilService,
            JobDescriptionRepository jobDescriptionRepository,
            PageAdminAccountService pageAdminAccountService
    ) {
        this.conversationRepository = conversationRepository;
        this.slotRepository = slotRepository;
        this.notificationRepository = notificationRepository;
        this.vofficeAvailabilityService = vofficeAvailabilityService;
        this.channelMessagingService = channelMessagingService;
        this.slotSelectionResolver = slotSelectionResolver;
        this.properties = properties;
        this.candidateProfileService = candidateProfileService;
        this.recruitmentCouncilService = recruitmentCouncilService;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.pageAdminAccountService = pageAdminAccountService;
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
        conversation.setJobDescriptionId(resolveJobDescriptionId(candidateProfile));
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
            if (shouldRestartScheduling(candidateProfile, activeConversation.get())) {
                log.info("Resetting active interview conversation for senderId={} because candidate profile was updated after the conversation. conversationId={}, state={}",
                        senderId,
                        activeConversation.get().getId(),
                        activeConversation.get().getState());
                cancelAndReleaseConversation(activeConversation.get());
            } else {
                log.info("Skipping auto-start for senderId={} because an active interview conversation already exists. conversationId={}, state={}",
                        senderId,
                        activeConversation.get().getId(),
                        activeConversation.get().getState());
                return false;
            }
        }
        Optional<InterviewConversation> latestConversation =
                conversationRepository.findFirstByCandidateSenderIdOrderByUpdatedAtDesc(senderId);
        if (latestConversation.isPresent()
                && latestConversation.get().getState() == InterviewConversationState.CONFIRMED
                && !shouldRestartScheduling(candidateProfile, latestConversation.get())) {
            log.info("Skipping auto-start for senderId={} because the latest interview conversation is already confirmed. conversationId={}",
                    senderId,
                    latestConversation.get().getId());
            return false;
        }

        InterviewConversation conversation = new InterviewConversation();
        conversation.setCandidateSenderId(candidateProfile.getSenderId());
        conversation.setCandidateName(candidateProfile.getCandidateName());
        conversation.setAppliedPosition(candidateProfile.getAppliedPosition());
        conversation.setDepartment(candidateProfile.getDepartment());
        conversation.setCvScore(candidateProfile.getCvScore());
        conversation.setJobDescriptionId(resolveJobDescriptionId(candidateProfile));
        conversation.setState(InterviewConversationState.AWAITING_SLOT_SELECTION);
        conversation = conversationRepository.save(conversation);

        List<InterviewSlot> offeredSlots = reserveFreshSlots(conversation, candidateProfile.getDepartment());
        channelMessagingService.sendText(
                candidateProfile.getSenderId(),
                buildCandidateOfferMessage(conversation, offeredSlots, true, false)
        );
        log.info("Auto-started interview scheduling for passed candidate senderId={}", senderId);
        return true;
    }

    @Transactional
    public void autoStartSchedulingForPassedCandidates() {
        if (!properties.enabled()) {
            return;
        }
        for (CandidateProfile candidateProfile : candidateProfileService.getAllPassedCandidates()) {
            try {
                autoStartSchedulingForPassedCandidateIfNeeded(candidateProfile.getSenderId());
            } catch (Exception exception) {
                log.error("Failed to auto-start interview scheduling for passed candidate senderId={}",
                        candidateProfile.getSenderId(),
                        exception);
            }
        }
    }

    @Transactional
    public void resetSchedulingForCandidatesWithoutPassCv() {
        if (!properties.enabled()) {
            return;
        }
        for (CandidateProfile candidateProfile : candidateProfileService.getAllNotPassedCandidates()) {
            for (InterviewConversation conversation :
                    conversationRepository.findAllByCandidateSenderIdOrderByUpdatedAtDesc(candidateProfile.getSenderId())) {
                if (conversation.getState() == InterviewConversationState.CANCELLED) {
                    continue;
                }
                InterviewConversationState previousState = conversation.getState();
                cancelPendingNotifications(conversation.getId());
                cancelAndReleaseConversation(conversation);
                log.info("Reset interview scheduling for senderId={} because candidate profile is currently passCv=false. conversationId={}, previousState={}",
                        candidateProfile.getSenderId(),
                        conversation.getId(),
                        previousState);
            }
        }
    }

    private boolean shouldRestartScheduling(CandidateProfile candidateProfile, InterviewConversation conversation) {
        return candidateProfile != null
                && candidateProfile.getUpdatedAt() != null
                && conversation != null
                && conversation.getUpdatedAt() != null
                && candidateProfile.getUpdatedAt().isAfter(conversation.getUpdatedAt());
    }

    @Transactional
    public boolean handleCandidateReplyIfApplicable(String senderId, String messageText) {
        if (!properties.enabled()) {
            return false;
        }
        Optional<InterviewConversation> latestConversation =
                conversationRepository.findFirstByCandidateSenderIdOrderByUpdatedAtDesc(senderId);
        if (latestConversation.isPresent()
                && RESCHEDULABLE_CANDIDATE_STATES.contains(latestConversation.get().getState())
                && isRescheduleRequest(messageText)) {
            String inlineReason = extractInlineRescheduleReason(messageText);
            if (StringUtils.hasText(inlineReason)) {
                initiateCandidateRescheduleRequest(latestConversation.get(), inlineReason);
            } else {
                initiateCandidateRescheduleRequest(latestConversation.get());
            }
            return true;
        }

        Optional<InterviewConversation> waitingForReason = conversationRepository
                .findFirstByCandidateSenderIdAndStateOrderByUpdatedAtDesc(
                        senderId,
                        InterviewConversationState.AWAITING_RESCHEDULE_REASON
                );
        if (waitingForReason.isPresent()) {
            captureCandidateRescheduleReason(waitingForReason.get(), messageText);
            return true;
        }

        Optional<InterviewConversation> waitingHrReview = conversationRepository
                .findFirstByCandidateSenderIdAndStateOrderByUpdatedAtDesc(
                        senderId,
                        InterviewConversationState.HR_RESCHEDULE_REVIEW
                );
        if (waitingHrReview.isPresent()) {
            channelMessagingService.sendText(
                    senderId,
                    "Mình đã gửi yêu cầu đổi lịch của bạn tới HR và đang chờ phản hồi. Khi HR duyệt, mình sẽ gửi danh sách lịch mới."
            );
            return true;
        }

        if (latestConversation.isPresent()
                && latestConversation.get().getState() == InterviewConversationState.COUNCIL_REJECTED) {
            channelMessagingService.sendText(
                    senderId,
                    "Noi bo cong ty dang ban nen lich phong van truoc do chua duoc chap thuan. Neu ban muon dat lich lai, hay nhan "
                            + buildCandidateRescheduleKeywordHint() + "."
            );
            return true;
        }

        Optional<InterviewConversation> conversationOptional =
                conversationRepository.findFirstByCandidateSenderIdAndStateInOrderByUpdatedAtDesc(senderId, ACTIVE_CANDIDATE_STATES);
        if (conversationOptional.isEmpty()) {
            return false;
        }

        InterviewConversation conversation = conversationOptional.get();
        if (conversation.getState() == InterviewConversationState.CANDIDATE_REVIEWING_COUNCIL_SLOT) {
            handleCandidateCouncilSlotReply(conversation, messageText);
            return true;
        }

        if (conversation.getState() == InterviewConversationState.HR_PENDING && !isRescheduleRequest(messageText)) {
            channelMessagingService.sendText(
                    senderId,
                    "MÃ¬nh Ä‘Ã£ giá»¯ lá»‹ch báº¡n chá»n vÃ  Ä‘ang chá» HR xÃ¡c nháº­n. Náº¿u báº¡n muá»‘n Ä‘á»•i lá»‹ch, hÃ£y nháº¯n "
                            + buildCandidateRescheduleKeywordHint() + "."
            );
            return true;
        }

        if (conversation.getState() == InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT && !isRescheduleRequest(messageText)) {
            channelMessagingService.sendText(
                    senderId,
                    "HR da xac nhan lich ban chon va he thong dang cho HR phan cong Hoi dong phong van. Neu ban muon doi lich, hay nhan "
                            + buildCandidateRescheduleKeywordHint() + "."
            );
            return true;
        }

        if (conversation.getState() == InterviewConversationState.COUNCIL_PENDING && !isRescheduleRequest(messageText)) {
            channelMessagingService.sendText(
                    senderId,
                    "HR da duyet lich cua ban va he thong dang cho Hoi dong phong van xac nhan lan cuoi. Neu ban muon doi lich, hay nhan "
                            + buildCandidateRescheduleKeywordHint() + "."
            );
            return true;
        }

        if (conversation.getState() == InterviewConversationState.HR_PENDING) {
            if (isRescheduleRequest(messageText)) {
                initiateCandidateRescheduleRequest(conversation);
            } else {
                channelMessagingService.sendText(senderId,
                        "Mình đã giữ lịch bạn chọn và đang chờ HR xác nhận. Nếu bạn muốn đổi lịch, hãy nhắn 'đổi lịch'.");
            }
            return true;
        }

        if (conversation.getState() == InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT) {
            if (isRescheduleRequest(messageText)) {
                initiateCandidateRescheduleRequest(conversation);
            } else {
                channelMessagingService.sendText(
                        senderId,
                        "HR da xac nhan lich ban chon va he thong dang cho HR phan cong Hoi dong phong van. Neu ban muon doi lich, hay nhan 'doi lich'."
                );
            }
            return true;
        }

        if (conversation.getState() == InterviewConversationState.COUNCIL_PENDING) {
            if (isRescheduleRequest(messageText)) {
                initiateCandidateRescheduleRequest(conversation);
            } else {
                channelMessagingService.sendText(
                        senderId,
                        "HR da duyet lich cua ban va he thong dang cho Hoi dong phong van xac nhan lan cuoi. Neu ban muon doi lich, hay nhan 'doi lich'."
                );
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

        if (handleCouncilSlotSelectionIfApplicable(senderId, messageText)) {
            return true;
        }
        if (handleCouncilAssignmentIfApplicable(senderId, messageText)) {
            return true;
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
        if (pendingNotifications.isEmpty()) {
            return false;
        }
        HrInterviewNotification notification = resolvePendingNotification(pendingNotifications, conversationId);
        if (notification == null) {
            channelMessagingService.sendText(senderId,
                    "Không tìm thấy yêu cầu phỏng vấn đang chờ xử lý. Nếu có nhiều lịch chờ, hãy trả lời theo mẫu: XAC NHAN <ma> hoặc DOI LICH <ma>.");
            return true;
        }

        InterviewConversation conversation = conversationRepository.findById(notification.getConversationId())
                .orElseThrow(() -> new IllegalStateException("Interview conversation not found: " + notification.getConversationId()));

        if (action == HrAction.CONFIRM) {
            if (notification.getNotificationKind() == HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION) {
                confirmByCouncil(conversation, notification);
            } else if (notification.getNotificationKind() == HrInterviewNotificationKind.CANDIDATE_RESCHEDULE_REQUEST) {
                rejectCandidateRescheduleRequest(conversation, notification);
            } else {
                confirmByHr(conversation, notification);
            }
        } else {
            if (notification.getNotificationKind() == HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION) {
                requestCouncilAlternativeSlot(conversation, notification);
            } else if (notification.getNotificationKind() == HrInterviewNotificationKind.CANDIDATE_RESCHEDULE_REQUEST) {
                approveCandidateRescheduleRequest(conversation, notification);
            } else {
                requestReschedule(conversation, notification);
            }
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
            if (conversation == null || (conversation.getState() != InterviewConversationState.HR_PENDING
                    && conversation.getState() != InterviewConversationState.COUNCIL_PENDING)) {
                if (conversation != null
                        && conversation.getState() == InterviewConversationState.HR_RESCHEDULE_REVIEW
                        && notification.getNotificationKind() == HrInterviewNotificationKind.CANDIDATE_RESCHEDULE_REQUEST) {
                    notification.setStatus(HrInterviewNotificationStatus.REJECTED);
                    notification.setRespondedAt(now);
                    restoreConversationAfterRescheduleDenied(conversation);
                    channelMessagingService.sendText(
                            conversation.getCandidateSenderId(),
                            "HR chưa phản hồi kịp yêu cầu đổi lịch. Tạm thời hệ thống giữ nguyên lịch phỏng vấn cũ của bạn."
                    );
                    channelMessagingService.sendText(
                            notification.getHrRecipientId(),
                            "Yêu cầu đổi lịch của ứng viên cho mã " + conversation.getId()
                                    + " đã hết hạn, hệ thống giữ nguyên lịch cũ."
                    );
                }
                continue;
            }
            if (notification.getNotificationKind() == HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION) {
                autoRejectByCouncilTimeout(conversation, notification);
                continue;
            }
            notification.setStatus(HrInterviewNotificationStatus.AUTO_CONFIRMED);
            notification.setRespondedAt(now);
            if (notifyCouncilAfterHrConfirmation(conversation, notification)) {
                continue;
            }
            moveConversationToAwaitingCouncilAssignment(conversation, notification.getHrRecipientId());
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
                    .append("[slotId=").append(slot.getId()).append("] ")
                    .append(formatSlotLabel(slot))
                    .append(" | ")
                    .append(mapSlotStatus(slot))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    @Transactional
    public String buildSlotDetail(Long slotId) {
        ensureEnabled();
        InterviewSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new IllegalStateException("Interview slot not found: " + slotId));

        StringBuilder builder = new StringBuilder();
        builder.append("Chi tiết lịch [slotId=").append(slot.getId()).append("]\n");
        builder.append("Thời gian: ").append(formatSlotLabel(slot)).append('\n');
        builder.append("Phòng ban: ").append(slot.getDepartment()).append('\n');
        builder.append("Trạng thái: ").append(mapSlotStatus(slot)).append('\n');
        if (StringUtils.hasText(slot.getStatusNote())) {
            builder.append("Ghi chú nội bộ: ").append(slot.getStatusNote()).append('\n');
        }

        if (slot.getBookedByConversationId() != null) {
            conversationRepository.findById(slot.getBookedByConversationId()).ifPresent(conversation -> {
                builder.append("Ứng viên: ").append(conversation.getCandidateName()).append('\n');
                builder.append("Vị trí: ").append(conversation.getAppliedPosition()).append('\n');
                builder.append("Điểm CV: ").append(conversation.getCvScore()).append('\n');
                builder.append("Trạng thái hội thoại: ").append(conversation.getState()).append('\n');
                if (StringUtils.hasText(conversation.getLastRescheduleReason())) {
                    builder.append("Lý do đổi lịch gần nhất: ").append(conversation.getLastRescheduleReason()).append('\n');
                }
            });
        }
        return builder.toString().trim();
    }

    @Transactional
    public String editScheduleByHr(Long slotId, String reason, String hrSenderId) {
        ensureEnabled();
        if (!StringUtils.hasText(reason)) {
            throw new IllegalStateException("Reason is required when HR edits a schedule");
        }

        InterviewSlot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new IllegalStateException("Interview slot not found: " + slotId));

        Long lockedConversationId = slot.getLockedByConversationId();
        Long bookedConversationId = slot.getBookedByConversationId();
        slot.markUnavailable(reason.trim());

        if (bookedConversationId != null) {
            conversationRepository.findById(bookedConversationId).ifPresent(conversation -> {
                cancelPendingNotifications(conversation.getId());
                conversation.setSelectedSlotId(null);
                conversation.setSelectedCouncilId(null);
                conversation.setSelectedCouncilSenderId(null);
                conversation.setHrDecisionDeadlineAt(null);
                conversation.setRescheduleSourceState(null);
                conversation.setLastRescheduleReason(reason.trim());
                conversation.setState(InterviewConversationState.RESCHEDULE_REQUESTED);
                channelMessagingService.sendText(
                        conversation.getCandidateSenderId(),
                        "Xin lỗi, lịch phỏng vấn đã cần điều chỉnh do sắp xếp nội bộ. Mình gửi bạn các khung giờ mới để chọn lại."
                );
                reOfferSlots(conversation, true);
            });
            return "Đã sửa lịch slotId=" + slotId + " và gửi lại luồng đổi lịch cho ứng viên.";
        }

        if (lockedConversationId != null) {
            conversationRepository.findById(lockedConversationId).ifPresent(conversation -> {
                conversation.setLastRescheduleReason(reason.trim());
                channelMessagingService.sendText(
                        conversation.getCandidateSenderId(),
                        "Một khung giờ bạn đang xem vừa được điều chỉnh nội bộ. Mình gửi lại danh sách mới để bạn chọn."
                );
                reOfferSlots(conversation, false);
            });
            return "Đã sửa lịch slotId=" + slotId + " và cập nhật lại danh sách cho ứng viên đang giữ slot.";
        }

        return "Đã đánh dấu slotId=" + slotId + " là không khả dụng. Lý do: " + reason.trim();
    }

    @Transactional
    public String buildCouncilConfirmedSchedule(String councilSenderId) {
        List<InterviewConversation> conversations = conversationRepository
                .findAllBySelectedCouncilSenderIdAndStateOrderByUpdatedAtDesc(councilSenderId, InterviewConversationState.CONFIRMED);
        if (conversations.isEmpty()) {
            return "Khong co lich phong van.";
        }

        StringBuilder builder = new StringBuilder("Lich phong van da xac nhan cua Hoi dong:\n");
        for (InterviewConversation conversation : conversations) {
            String slotLabel = conversation.getSelectedSlotId() == null
                    ? "Chua co slot"
                    : slotRepository.findById(conversation.getSelectedSlotId())
                    .map(this::formatSlotLabel)
                    .orElse("Khong tim thay slot");
            builder.append("Ma: ").append(conversation.getId()).append('\n')
                    .append("Ung vien: ").append(conversation.getCandidateName()).append('\n')
                    .append("Vi tri: ").append(conversation.getAppliedPosition()).append('\n')
                    .append("Thoi gian: ").append(slotLabel).append("\n\n");
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
        conversation.setSelectedSlotId(null);
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
        conversation.setSelectionLockExpiresAt(null);
        conversation.setHrDecisionDeadlineAt(null);
        conversation.setLastRescheduleReason(null);
        conversation.setRescheduleSourceState(null);
    }

    private List<InterviewSlot> reserveFreshSlots(InterviewConversation conversation, String department) {
        releasePresentedSlots(conversation);
        String resolvedDepartment = resolveDepartment(department);
        syncSlotsFromVOffice(resolvedDepartment);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime toTime = now.plusDays(8);
        List<InterviewSlot> upcomingSlots = slotRepository.findUpcomingForUpdateByDepartment(
                resolvedDepartment,
                now,
                toTime
        );
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
        conversation.setDepartment(resolvedDepartment);
        conversation.setSelectedSlotId(null);
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
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

        releaseOtherPresentedSlots(conversation, lockedSlot.getId());
        OffsetDateTime hrDeadline = OffsetDateTime.now().plusMinutes(properties.hrResponseTimeoutMinutes());
        lockedSlot.softLock(conversation.getId(), hrDeadline);
        conversation.setSelectedSlotId(lockedSlot.getId());
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
        conversation.setSelectionLockExpiresAt(null);
        conversation.setState(InterviewConversationState.HR_PENDING);
        conversation.setLastRescheduleReason(null);
        conversation.setRescheduleSourceState(null);
        conversation.setHrDecisionDeadlineAt(hrDeadline);

        HrInterviewNotification notification = new HrInterviewNotification();
        notification.setConversationId(conversation.getId());
        notification.setHrRecipientId(resolveHrRecipientId(conversation.getCandidateSenderId()));
        notification.setInterviewSlotId(lockedSlot.getId());
        notification.setNotificationKind(HrInterviewNotificationKind.BOOKING_CONFIRMATION);
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
        notification.setStatus(HrInterviewNotificationStatus.CONFIRMED);
        notification.setRespondedAt(OffsetDateTime.now());

        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã xác nhận lịch phỏng vấn cho mã " + conversation.getId() + "."
        );
        if (notifyCouncilAfterHrConfirmation(conversation, notification)) {
            return;
        }
        moveConversationToAwaitingCouncilAssignment(conversation, notification.getHrRecipientId());
    }

    private boolean notifyCouncilAfterHrConfirmation(InterviewConversation conversation, HrInterviewNotification sourceNotification) {
        List<RecruitmentCouncil> councils = resolveCouncilsForConversation(conversation);
        if (councils.isEmpty()) {
            return false;
        }
        dispatchCouncilConfirmationRequests(
                conversation,
                councils,
                sourceNotification == null ? null : sourceNotification.getHrRecipientId(),
                false
        );
        return true;
    }

    private void confirmByCouncil(InterviewConversation conversation, HrInterviewNotification notification) {
        if (conversation.getState() != InterviewConversationState.COUNCIL_PENDING) {
            channelMessagingService.sendText(notification.getHrRecipientId(), "Lich nay khong con cho Hoi dong xac nhan.");
            return;
        }
        RecruitmentCouncil council = recruitmentCouncilService.findByRepresentativeSenderId(notification.getHrRecipientId());
        bookSelectedSlotForFinalConfirmation(conversation);
        conversation.setSelectedCouncilId(council == null ? null : council.getId());
        conversation.setSelectedCouncilSenderId(notification.getHrRecipientId());
        conversation.setState(InterviewConversationState.CONFIRMED);
        conversation.setHrDecisionDeadlineAt(null);
        notification.setStatus(HrInterviewNotificationStatus.CONFIRMED);
        notification.setRespondedAt(OffsetDateTime.now());
        cancelOtherCouncilNotifications(conversation.getId(), notification.getId());
        channelMessagingService.sendText(notification.getHrRecipientId(), "Da xac nhan tham gia phong van ma " + conversation.getId() + ".");
        channelMessagingService.sendText(conversation.getCandidateSenderId(), buildFinalCandidateConfirmation(conversation));
    }

    private void rejectByCouncil(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.REJECTED);
        notification.setRespondedAt(OffsetDateTime.now());
        if (hasOtherPendingCouncilNotifications(conversation.getId())) {
            channelMessagingService.sendText(notification.getHrRecipientId(), "Da ghi nhan Hoi dong tu choi lich ma " + conversation.getId() + ".");
            return;
        }
        releaseBookedSlot(conversation);
        conversation.setState(InterviewConversationState.COUNCIL_REJECTED);
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
        conversation.setHrDecisionDeadlineAt(null);
        conversation.setRescheduleSourceState(null);
        channelMessagingService.sendText(notification.getHrRecipientId(),
                "Tat ca Hoi dong da tu choi lich ma " + conversation.getId() + ". Noi bo cong ty co viec ban, vui long lam viec lai voi ung vien neu can dat lich moi.");
        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Xin loi ban, Khung gio do hoi dong ban. Hay request cho HR dat lich."
        );
    }

    private void autoRejectByCouncilTimeout(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.REJECTED);
        notification.setRespondedAt(OffsetDateTime.now());
        if (hasOtherPendingCouncilNotifications(conversation.getId())) {
            return;
        }
        rejectByCouncil(conversation, notification);
    }

    private void requestCouncilAlternativeSlot(InterviewConversation conversation, HrInterviewNotification notification) {
        if (conversation.getState() != InterviewConversationState.COUNCIL_PENDING) {
            channelMessagingService.sendText(notification.getHrRecipientId(), "Lich nay khong con cho Hoi dong doi lich.");
            return;
        }
        releaseBookedSlot(conversation);
        conversation.setSelectedSlotId(null);
        conversation.setState(InterviewConversationState.COUNCIL_SELECTING_SLOT);
        conversation.setHrDecisionDeadlineAt(OffsetDateTime.now().plusMinutes(properties.councilResponseTimeoutMinutes()));
        notification.setResponseDeadlineAt(conversation.getHrDecisionDeadlineAt());

        List<InterviewSlot> offeredSlots = reserveFreshSlots(conversation, conversation.getDepartment());
        RecruitmentCouncil council = recruitmentCouncilService.findByRepresentativeSenderId(notification.getHrRecipientId());
        conversation.setSelectedCouncilId(council == null ? null : council.getId());
        conversation.setSelectedCouncilSenderId(notification.getHrRecipientId());
        conversation.setState(InterviewConversationState.COUNCIL_SELECTING_SLOT);

        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Chon khung gio moi de de xuat cho ung vien ma " + conversation.getId() + ":\n\n"
                        + formatSlotOptions(offeredSlots)
                        + "\n\nTra loi bang so thu tu, ngay hoac gio."
        );
        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Hoi dong phong van dang de xuat khung gio khac. Minh se gui ban slot moi ngay khi Hoi dong chon xong."
        );
    }

    private boolean handleCouncilAssignmentIfApplicable(String senderId, String messageText) {
        List<InterviewConversation> awaitingConversations = findAwaitingCouncilAssignmentConversationsForHr(senderId);
        CouncilAssignmentChoice choice = parseCouncilAssignmentChoice(messageText);
        if (choice == null && !awaitingConversations.isEmpty()) {
            RecruitmentCouncil quickCouncil = recruitmentCouncilService.findActiveCouncilByReference(messageText);
            if (quickCouncil != null) {
                choice = new CouncilAssignmentChoice(messageText.trim(), null);
            }
        }
        if (choice == null) {
            return false;
        }
        CouncilAssignmentChoice resolvedChoice = choice;

        if (awaitingConversations.isEmpty()) {
            channelMessagingService.sendText(
                    senderId,
                    "Khong co lich nao dang cho ban chon Hoi dong phong van."
            );
            return true;
        }

        if (resolvedChoice.conversationId() == null && awaitingConversations.size() > 1) {
            channelMessagingService.sendText(
                    senderId,
                    "Ban dang co nhieu lich cho chon Hoi dong. Hay tra loi theo mau: CHON HD <ma-hoi-dong> <ma-cuoc-hen>."
            );
            return true;
        }

        InterviewConversation conversation = awaitingConversations.stream()
                .filter(item -> resolvedChoice.conversationId() == null || resolvedChoice.conversationId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (conversation == null) {
            channelMessagingService.sendText(
                    senderId,
                    "Khong tim thay lich dang cho chon Hoi dong voi ma " + resolvedChoice.conversationId() + "."
            );
            return true;
        }

        RecruitmentCouncil council = recruitmentCouncilService.findActiveCouncilByReference(resolvedChoice.councilReference());
        if (council == null) {
            channelMessagingService.sendText(
                    senderId,
                    "Khong tim thay Hoi dong '" + resolvedChoice.councilReference() + "'.\n"
                            + recruitmentCouncilService.buildActiveCouncilSummary()
                            + "\nTra loi: CHON HD <ma-hoi-dong> " + conversation.getId()
            );
            return true;
        }

        assignCouncilAndContinue(conversation, senderId, council);
        return true;
    }

    private boolean handleCouncilSlotSelectionIfApplicable(String senderId, String messageText) {
        List<HrInterviewNotification> pendingNotifications =
                notificationRepository.findAllByHrRecipientIdAndStatusOrderBySentAtDesc(
                        senderId,
                        HrInterviewNotificationStatus.PENDING
                );
        if (pendingNotifications.isEmpty()) {
            return false;
        }

        Long conversationId = parseConversationReference(messageText);
        for (HrInterviewNotification notification : pendingNotifications) {
            if (notification.getNotificationKind() != HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION) {
                continue;
            }
            if (conversationId != null && !conversationId.equals(notification.getConversationId())) {
                continue;
            }
            Optional<InterviewConversation> conversationOptional = conversationRepository.findById(notification.getConversationId());
            if (conversationOptional.isEmpty()
                    || conversationOptional.get().getState() != InterviewConversationState.COUNCIL_SELECTING_SLOT) {
                continue;
            }
            handleCouncilSlotChoice(conversationOptional.get(), notification, messageText);
            return true;
        }
        return false;
    }

    private void handleCouncilSlotChoice(
            InterviewConversation conversation,
            HrInterviewNotification notification,
            String messageText
    ) {
        List<InterviewSlot> offeredSlots = getPresentedSlots(conversation);
        if (offeredSlots.isEmpty()) {
            requestCouncilAlternativeSlot(conversation, notification);
            return;
        }

        InterviewSlotSelectionResolver.Resolution resolution = slotSelectionResolver.resolve(messageText, offeredSlots);
        if (resolution.type() != InterviewSlotSelectionResolver.ResolutionType.SELECTED) {
            channelMessagingService.sendText(
                    notification.getHrRecipientId(),
                    "Chua xac dinh duoc khung gio moi. Hay tra loi bang so thu tu, ngay hoac gio trong danh sach:\n\n"
                            + formatSlotOptions(offeredSlots)
            );
            return;
        }

        Integer optionNumber = resolution.optionNumber();
        if (optionNumber == null || optionNumber < 1 || optionNumber > offeredSlots.size()) {
            channelMessagingService.sendText(notification.getHrRecipientId(), "Lua chon khung gio khong hop le.");
            return;
        }

        InterviewSlot chosen = offeredSlots.get(optionNumber - 1);
        InterviewSlot lockedSlot = slotRepository.findByIdForUpdate(chosen.getId())
                .orElseThrow(() -> new IllegalStateException("Interview slot not found: " + chosen.getId()));
        if (lockedSlot.getStatus() != InterviewSlotStatus.SOFT_LOCKED
                || !conversation.getId().equals(lockedSlot.getLockedByConversationId())) {
            requestCouncilAlternativeSlot(conversation, notification);
            return;
        }

        releaseOtherPresentedSlots(conversation, lockedSlot.getId());
        OffsetDateTime candidateDeadline = OffsetDateTime.now().plusMinutes(properties.hrResponseTimeoutMinutes());
        lockedSlot.softLock(conversation.getId(), candidateDeadline);
        conversation.setSelectedSlotId(lockedSlot.getId());
        conversation.setState(InterviewConversationState.CANDIDATE_REVIEWING_COUNCIL_SLOT);
        conversation.setSelectionLockExpiresAt(null);
        conversation.setHrDecisionDeadlineAt(candidateDeadline);
        notification.setInterviewSlotId(lockedSlot.getId());
        notification.setResponseDeadlineAt(candidateDeadline);

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Slot do hoi dong phong van ban. Ban co the doi sang slot nay khong?\n"
                        + formatSlotLabel(lockedSlot)
                        + "\n\nTra loi: DONG Y " + conversation.getId() + " hoac DOI LICH " + conversation.getId() + "."
        );
        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Da gui slot moi cho ung vien xac nhan. Ma: " + conversation.getId()
        );
    }

    private void handleCandidateCouncilSlotReply(InterviewConversation conversation, String messageText) {
        if (isCandidateAcceptsProposedSlot(messageText)) {
            HrInterviewNotification notification = findPendingCouncilNotification(conversation)
                    .orElse(null);
            bookSelectedSlotForFinalConfirmation(conversation);
            if (notification != null) {
                RecruitmentCouncil council = recruitmentCouncilService.findByRepresentativeSenderId(notification.getHrRecipientId());
                conversation.setSelectedCouncilId(council == null ? null : council.getId());
                conversation.setSelectedCouncilSenderId(notification.getHrRecipientId());
                notification.setStatus(HrInterviewNotificationStatus.CONFIRMED);
                notification.setRespondedAt(OffsetDateTime.now());
                cancelOtherCouncilNotifications(conversation.getId(), notification.getId());
                channelMessagingService.sendText(
                        notification.getHrRecipientId(),
                        "Ung vien da dong y slot moi. Lich phong van ma " + conversation.getId() + " da duoc dat."
                );
                channelMessagingService.sendText(
                        resolveHrRecipientId(conversation.getCandidateSenderId()),
                        "Ung vien da dong y slot Hoi dong de xuat. Lich phong van ma " + conversation.getId() + " da duoc dat."
                );
            }
            conversation.setState(InterviewConversationState.CONFIRMED);
            conversation.setHrDecisionDeadlineAt(null);
            channelMessagingService.sendText(conversation.getCandidateSenderId(), buildFinalCandidateConfirmation(conversation));
            return;
        }

        if (isRescheduleRequest(messageText) || isCandidateRejectsProposedSlot(messageText)) {
            initiateCandidateRescheduleRequest(conversation);
            return;
        }

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Ban vui long tra loi DONG Y " + conversation.getId()
                        + " neu chap nhan slot Hoi dong de xuat, hoac DOI LICH "
                        + conversation.getId() + " neu muon chon khung gio khac."
        );
    }

    private void requestReschedule(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.RESCHEDULE_REQUESTED);
        notification.setRespondedAt(OffsetDateTime.now());
        resetConversationForRescheduleSelection(conversation);
        reOfferSlots(conversation, true);
        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã yêu cầu ứng viên chọn lại lịch cho mã " + conversation.getId() + "."
        );
    }

    private void initiateCandidateRescheduleRequest(InterviewConversation conversation) {
        conversation.setRescheduleSourceState(conversation.getState());
        conversation.setState(InterviewConversationState.AWAITING_RESCHEDULE_REASON);
        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Mình đã nhận yêu cầu đổi lịch. Bạn vui lòng cho biết lý do đổi lịch để mình gửi HR duyệt."
        );
    }

    private void initiateCandidateRescheduleRequest(InterviewConversation conversation, String reason) {
        conversation.setRescheduleSourceState(conversation.getState());
        conversation.setState(InterviewConversationState.AWAITING_RESCHEDULE_REASON);
        captureCandidateRescheduleReason(conversation, reason);
    }

    private void captureCandidateRescheduleReason(InterviewConversation conversation, String reason) {
        if (!StringUtils.hasText(reason)) {
            channelMessagingService.sendText(
                    conversation.getCandidateSenderId(),
                    "Bạn vui lòng nêu rõ lý do đổi lịch, ví dụ: bận họp đột xuất, trùng lịch công việc, hoặc lý do cá nhân."
            );
            return;
        }

        String capturedReason = extractInlineRescheduleReason(reason);
        if (!StringUtils.hasText(capturedReason)) {
            capturedReason = reason.trim();
        }
        if (isPureRescheduleRequest(capturedReason)) {
            channelMessagingService.sendText(
                    conversation.getCandidateSenderId(),
                    "Báº¡n vui lÃ²ng nÃªu rÃµ lÃ½ do Ä‘á»•i lá»‹ch, vÃ­ dá»¥: báº­n há»p Ä‘á»™t xuáº¥t, trÃ¹ng lá»‹ch cÃ´ng viá»‡c, hoáº·c lÃ½ do cÃ¡ nhÃ¢n."
            );
            return;
        }

        cancelPendingNotifications(conversation.getId());
        conversation.setLastRescheduleReason(capturedReason);
        conversation.setState(InterviewConversationState.HR_RESCHEDULE_REVIEW);

        HrInterviewNotification notification = new HrInterviewNotification();
        notification.setConversationId(conversation.getId());
        notification.setHrRecipientId(resolveHrRecipientId(conversation.getCandidateSenderId()));
        notification.setInterviewSlotId(conversation.getSelectedSlotId());
        notification.setNotificationKind(HrInterviewNotificationKind.CANDIDATE_RESCHEDULE_REQUEST);
        notification.setStatus(HrInterviewNotificationStatus.PENDING);
        notification.setSentAt(OffsetDateTime.now());
        notification.setResponseDeadlineAt(OffsetDateTime.now().plusMinutes(properties.hrResponseTimeoutMinutes()));
        notification.setMessageBody(buildCandidateRescheduleRequestMessage(conversation));
        notificationRepository.save(notification);

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "Mình đã gửi yêu cầu đổi lịch của bạn tới HR. Khi HR duyệt, mình sẽ gửi danh sách lịch mới để bạn chọn."
        );
        channelMessagingService.sendText(notification.getHrRecipientId(), notification.getMessageBody());
    }

    private void approveCandidateRescheduleRequest(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.RESCHEDULE_REQUESTED);
        notification.setRespondedAt(OffsetDateTime.now());
        resetConversationForRescheduleSelection(conversation);
        reOfferSlots(conversation, true);
        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã duyệt yêu cầu đổi lịch cho mã " + conversation.getId() + ". Hệ thống đã gửi slot mới cho ứng viên."
        );
    }

    private void rejectCandidateRescheduleRequest(InterviewConversation conversation, HrInterviewNotification notification) {
        notification.setStatus(HrInterviewNotificationStatus.REJECTED);
        notification.setRespondedAt(OffsetDateTime.now());
        restoreConversationAfterRescheduleDenied(conversation);
        channelMessagingService.sendText(
                notification.getHrRecipientId(),
                "Đã giữ nguyên lịch cũ cho mã " + conversation.getId() + "."
        );
        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "HR chưa đồng ý đổi lịch ở thời điểm này. Mình đang giữ nguyên lịch phỏng vấn cũ của bạn."
        );
    }

    private void restoreConversationAfterRescheduleDenied(InterviewConversation conversation) {
        InterviewConversationState fallbackState = conversation.getRescheduleSourceState() != null
                ? conversation.getRescheduleSourceState()
                : InterviewConversationState.CONFIRMED;
        conversation.setState(fallbackState);
        conversation.setRescheduleSourceState(null);
    }

    private void resetConversationForRescheduleSelection(InterviewConversation conversation) {
        releaseBookedSlot(conversation);
        conversation.setState(InterviewConversationState.RESCHEDULE_REQUESTED);
        conversation.setSelectedSlotId(null);
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
        conversation.setLastPresentedSlotIds(null);
        conversation.setSelectionLockExpiresAt(null);
        conversation.setHrDecisionDeadlineAt(null);
        conversation.setRescheduleSourceState(null);
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
            boolean bookedByConversation = conversation.getId().equals(slot.getBookedByConversationId());
            boolean lockedByConversation = conversation.getId().equals(slot.getLockedByConversationId());
            if (bookedByConversation || lockedByConversation) {
                slot.setBookedByConversationId(null);
                slot.setBookedAt(null);
                slot.release();
            }
        });
    }

    private void extendSelectedSlotHold(InterviewConversation conversation, OffsetDateTime holdUntil) {
        if (conversation.getSelectedSlotId() == null) {
            return;
        }
        slotRepository.findByIdForUpdate(conversation.getSelectedSlotId()).ifPresent(slot -> {
            if (slot.getStatus() == InterviewSlotStatus.AVAILABLE) {
                slot.softLock(conversation.getId(), holdUntil);
                return;
            }
            if (slot.getStatus() == InterviewSlotStatus.SOFT_LOCKED
                    && conversation.getId().equals(slot.getLockedByConversationId())) {
                slot.softLock(conversation.getId(), holdUntil);
            }
        });
    }

    private void bookSelectedSlotForFinalConfirmation(InterviewConversation conversation) {
        if (conversation.getSelectedSlotId() == null) {
            throw new IllegalStateException("Selected interview slot is required before final confirmation");
        }
        InterviewSlot slot = slotRepository.findByIdForUpdate(conversation.getSelectedSlotId())
                .orElseThrow(() -> new IllegalStateException("Selected interview slot not found: " + conversation.getSelectedSlotId()));
        boolean alreadyBookedByConversation = slot.getStatus() == InterviewSlotStatus.BOOKED
                && conversation.getId().equals(slot.getBookedByConversationId());
        if (alreadyBookedByConversation) {
            return;
        }
        boolean heldByConversation = slot.getStatus() == InterviewSlotStatus.SOFT_LOCKED
                && conversation.getId().equals(slot.getLockedByConversationId());
        if (!heldByConversation && slot.getStatus() != InterviewSlotStatus.AVAILABLE) {
            throw new IllegalStateException("Selected interview slot is no longer available: " + conversation.getSelectedSlotId());
        }
        slot.book(conversation.getId());
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
                || normalized.contains("doi lich phong van")
                || normalized.contains("muon doi lich")
                || normalized.contains("xin doi lich")
                || normalized.contains("can doi lich")
                || normalized.contains("chuyen lich")
                || normalized.contains("doi buoi")
                || normalized.contains("doi ngay")
                || normalized.contains("ngay khac")
                || normalized.contains("hom khac")
                || normalized.contains("doi khung gio")
                || normalized.contains("doi gio")
                || normalized.contains("gio khac")
                || normalized.contains("lich khac")
                || normalized.contains("khung gio khac")
                || normalized.contains("reschedule")
                || normalized.contains("slot khac")
                || normalized.contains("another slot")
                || normalized.contains("another time");
    }

    private boolean isPureRescheduleRequest(String text) {
        String normalized = normalize(text);
        return normalized.equals("doi lich")
                || normalized.equals("doi lich phong van")
                || normalized.equals("doi ngay")
                || normalized.equals("ngay khac")
                || normalized.equals("hom khac")
                || normalized.equals("lich khac")
                || normalized.equals("doi gio")
                || normalized.equals("gio khac")
                || normalized.equals("slot khac")
                || normalized.equals("reschedule")
                || normalized.equals("another slot")
                || normalized.equals("another time")
                || normalized.equals("toi muon doi lich")
                || normalized.equals("minh muon doi lich")
                || normalized.equals("xin doi lich")
                || normalized.equals("can doi lich")
                || normalized.equals("toi muon doi lich phong van")
                || normalized.equals("minh muon doi lich phong van")
                || normalized.equals("toi muon ngay khac")
                || normalized.equals("minh muon ngay khac")
                || normalized.equals("toi muon hom khac")
                || normalized.equals("minh muon hom khac");
    }

    private String extractInlineRescheduleReason(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String[] separators = {" vì ", " vi ", " do ", " boi vi ", " because ", " since ", ": ", " - ", "- "};
        for (String separator : separators) {
            int index = lower.indexOf(separator);
            if (index >= 0) {
                String extracted = trimmed.substring(index + separator.length()).trim();
                return StringUtils.hasText(extracted) ? extracted : null;
            }
        }
        return isPureRescheduleRequest(trimmed) ? null : trimmed;
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
        OffsetDateTime localStart = slot.getStartTime().atZoneSameInstant(DISPLAY_ZONE).toOffsetDateTime();
        OffsetDateTime localEnd = slot.getEndTime().atZoneSameInstant(DISPLAY_ZONE).toOffsetDateTime();
        return localStart.format(SLOT_LABEL_FORMATTER)
                + " - "
                + localEnd.toLocalTime();
    }

    private String mapSlotStatus(InterviewSlot slot) {
        return switch (slot.getStatus()) {
            case AVAILABLE -> "Con trong";
            case SOFT_LOCKED -> "Dang tam giu";
            case BOOKED -> "Da dat";
            case UNAVAILABLE -> "Khong kha dung";
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

    private String buildCouncilInterviewConfirmationMessage(
            InterviewConversation conversation,
            InterviewSlot slot,
            RecruitmentCouncil council
    ) {
        return resolveCouncilDisplayName(council) + " co lich phong van\n"
                + "Ma: " + conversation.getId() + "\n"
                + "Ung vien: " + conversation.getCandidateName() + "\n"
                + "Vi tri: " + conversation.getAppliedPosition() + "\n"
                + "Diem CV: " + conversation.getCvScore() + "\n"
                + "Khung gio da chon: " + formatSlotLabel(slot) + "\n"
                + "Tra loi: XAC NHAN " + conversation.getId() + " hoac DOI LICH " + conversation.getId() + "\n"
                + "Neu qua " + Math.max(1, properties.councilResponseTimeoutMinutes() / 60)
                + " gio ma khong phan hoi, he thong se tu choi lich phong van.";
    }

    private String resolveCouncilDisplayName(RecruitmentCouncil council) {
        if (council == null) {
            return "Hoi dong";
        }
        return pageAdminAccountService.findActiveBySenderId(council.getRepresentativeSenderId())
                .map(account -> StringUtils.hasText(account.getDisplayName()) ? account.getDisplayName().trim() : null)
                .filter(StringUtils::hasText)
                .orElseGet(council::getName);
    }

    private String buildCandidateRescheduleRequestMessage(InterviewConversation conversation) {
        InterviewSlot slot = slotRepository.findById(conversation.getSelectedSlotId())
                .orElseThrow(() -> new IllegalStateException("Selected interview slot not found: " + conversation.getSelectedSlotId()));
        return "Yeu cau duyet doi lich phong van\n"
                + "Ma: " + conversation.getId() + "\n"
                + "Ung vien: " + conversation.getCandidateName() + "\n"
                + "Vi tri: " + conversation.getAppliedPosition() + "\n"
                + "Khung gio hien tai: " + formatSlotLabel(slot) + "\n"
                + "Ly do doi lich: " + conversation.getLastRescheduleReason() + "\n"
                + "Tra loi: DOI LICH " + conversation.getId() + " de duyet doi lich, hoac XAC NHAN " + conversation.getId() + " de giu lich cu.";
    }

    private String buildFinalCandidateConfirmation(InterviewConversation conversation) {
        InterviewSlot slot = slotRepository.findById(conversation.getSelectedSlotId())
                .orElseThrow(() -> new IllegalStateException("Selected interview slot not found: " + conversation.getSelectedSlotId()));
        return "Lịch phỏng vấn của bạn đã được xác nhận.\n"
                + "Thời gian: " + formatSlotLabel(slot) + "\n"
                + "Địa điểm: " + properties.interviewLocation() + "\n"
                + "Chuẩn bị: " + properties.preparationNotes();
    }

    private void moveConversationToAwaitingCouncilAssignment(InterviewConversation conversation, String hrSenderId) {
        Long resolvedJobDescriptionId = resolveConversationJobDescriptionId(conversation);
        if (resolvedJobDescriptionId != null) {
            conversation.setJobDescriptionId(resolvedJobDescriptionId);
        }
        conversation.setState(InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT);
        conversation.setSelectedCouncilId(null);
        conversation.setSelectedCouncilSenderId(null);
        conversation.setHrDecisionDeadlineAt(OffsetDateTime.now().plusMinutes(properties.councilResponseTimeoutMinutes()));
        extendSelectedSlotHold(conversation, conversation.getHrDecisionDeadlineAt());

        if (resolvedJobDescriptionId == null) {
            log.warn("HR confirmed interview conversationId={} but no jobDescriptionId could be resolved. candidateSenderId={}, appliedPosition={}",
                    conversation.getId(),
                    conversation.getCandidateSenderId(),
                    conversation.getAppliedPosition());
        } else {
            log.warn("HR confirmed interview conversationId={} but no active council mapping was found for jobDescriptionId={}. appliedPosition={}",
                    conversation.getId(),
                    resolvedJobDescriptionId,
                    conversation.getAppliedPosition());
        }

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                "HR da xac nhan lich ban chon. He thong dang cho HR phan cong Hoi dong phong van truoc khi xac nhan cuoi cung."
        );
        channelMessagingService.sendText(hrSenderId, buildCouncilAssignmentPrompt(conversation));
    }

    private String buildCouncilAssignmentPrompt(InterviewConversation conversation) {
        Long resolvedJobDescriptionId = resolveConversationJobDescriptionId(conversation);
        StringBuilder builder = new StringBuilder();
        builder.append("Khong tim thay Hoi dong cho lich phong van ma ").append(conversation.getId()).append(".\n")
                .append("Ung vien: ").append(conversation.getCandidateName()).append('\n')
                .append("Vi tri: ").append(conversation.getAppliedPosition()).append('\n');
        if (resolvedJobDescriptionId != null) {
            builder.append("JD: ").append(resolvedJobDescriptionId).append('\n');
        } else {
            builder.append("JD: chua xac dinh\n");
        }
        builder.append(recruitmentCouncilService.buildActiveCouncilSummary()).append('\n')
                .append("Tra loi: CHON HD <ma-hoi-dong> ").append(conversation.getId());
        return builder.toString();
    }

    private List<InterviewConversation> findAwaitingCouncilAssignmentConversationsForHr(String hrSenderId) {
        return conversationRepository.findAllByStateIn(List.of(InterviewConversationState.AWAITING_COUNCIL_ASSIGNMENT)).stream()
                .filter(conversation -> hrSenderId.equals(resolveHrRecipientId(conversation.getCandidateSenderId())))
                .sorted(Comparator.comparing(InterviewConversation::getUpdatedAt).reversed())
                .toList();
    }

    private void assignCouncilAndContinue(
            InterviewConversation conversation,
            String hrSenderId,
            RecruitmentCouncil council
    ) {
        Long resolvedJobDescriptionId = resolveConversationJobDescriptionId(conversation);
        if (resolvedJobDescriptionId != null) {
            conversation.setJobDescriptionId(resolvedJobDescriptionId);
            recruitmentCouncilService.ensureCouncilMappedToJob(resolvedJobDescriptionId, council);
        }
        conversation.setSelectedCouncilId(council.getId());
        conversation.setSelectedCouncilSenderId(council.getRepresentativeSenderId());
        dispatchCouncilConfirmationRequests(conversation, List.of(council), hrSenderId, true);
    }

    private void dispatchCouncilConfirmationRequests(
            InterviewConversation conversation,
            List<RecruitmentCouncil> councils,
            String hrSenderId,
            boolean assignedByHr
    ) {
        InterviewSlot slot = slotRepository.findByIdForUpdate(conversation.getSelectedSlotId())
                .orElseThrow(() -> new IllegalStateException("Selected interview slot not found: " + conversation.getSelectedSlotId()));
        OffsetDateTime councilDeadline = OffsetDateTime.now().plusMinutes(properties.councilResponseTimeoutMinutes());
        if (slot.getStatus() == InterviewSlotStatus.AVAILABLE
                || (slot.getStatus() == InterviewSlotStatus.SOFT_LOCKED
                && conversation.getId().equals(slot.getLockedByConversationId()))) {
            slot.softLock(conversation.getId(), councilDeadline);
        } else if (slot.getStatus() != InterviewSlotStatus.BOOKED
                || !conversation.getId().equals(slot.getBookedByConversationId())) {
            throw new IllegalStateException("Selected interview slot is no longer available for council confirmation: " + conversation.getSelectedSlotId());
        }

        conversation.setState(InterviewConversationState.COUNCIL_PENDING);
        conversation.setHrDecisionDeadlineAt(councilDeadline);

        for (RecruitmentCouncil council : councils) {
            HrInterviewNotification councilNotification = new HrInterviewNotification();
            councilNotification.setConversationId(conversation.getId());
            councilNotification.setHrRecipientId(council.getRepresentativeSenderId());
            councilNotification.setInterviewSlotId(slot.getId());
            councilNotification.setNotificationKind(HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION);
            councilNotification.setStatus(HrInterviewNotificationStatus.PENDING);
            councilNotification.setSentAt(OffsetDateTime.now());
            councilNotification.setResponseDeadlineAt(councilDeadline);
            councilNotification.setMessageBody(buildCouncilInterviewConfirmationMessage(conversation, slot, council));
            notificationRepository.save(councilNotification);
            channelMessagingService.sendText(council.getRepresentativeSenderId(), councilNotification.getMessageBody());
        }

        channelMessagingService.sendText(
                conversation.getCandidateSenderId(),
                assignedByHr
                        ? "HR da chon Hoi dong phong van. He thong dang cho Hoi dong xac nhan lan cuoi."
                        : "HR da xac nhan lich ban chon. He thong dang gui lich cho Hoi dong phong van xac nhan lan cuoi."
        );
        if (StringUtils.hasText(hrSenderId)) {
            String councilLabel = councils.stream()
                    .map(RecruitmentCouncil::getCode)
                    .collect(Collectors.joining(", "));
            channelMessagingService.sendText(
                    hrSenderId,
                    assignedByHr
                            ? "Da gan Hoi dong " + councilLabel + " va gui lich phong van ma " + conversation.getId() + " cho Hoi dong xac nhan."
                            : "Da gui lich phong van cho Hoi dong xac nhan. Ma: " + conversation.getId()
            );
        }
    }

    private String buildCandidateRescheduleKeywordHint() {
        return "'doi lich', 'doi gio', 'gio khac' hoac 'hom khac'";
    }

    private String resolveHrRecipientId(String candidateSenderId) {
        CandidateProfile candidateProfile = candidateProfileService.getPassedCandidateBySenderId(candidateSenderId);
        if (candidateProfile != null && StringUtils.hasText(candidateProfile.getAssignedHrSenderId())) {
            return candidateProfile.getAssignedHrSenderId();
        }
        return properties.hrRecipientId();
    }

    private Long resolveJobDescriptionId(CandidateProfile candidateProfile) {
        if (candidateProfile.getJobDescriptionId() != null) {
            return candidateProfile.getJobDescriptionId();
        }
        return findBestMatchingJobDescriptionId(candidateProfile.getAppliedPosition());
    }

    private List<RecruitmentCouncil> resolveCouncilsForConversation(InterviewConversation conversation) {
        Long jobDescriptionId = resolveConversationJobDescriptionId(conversation);
        if (jobDescriptionId != null && !jobDescriptionId.equals(conversation.getJobDescriptionId())) {
            conversation.setJobDescriptionId(jobDescriptionId);
        }
        List<RecruitmentCouncil> councils = jobDescriptionId == null
                ? List.of()
                : recruitmentCouncilService.findCouncilsForJob(jobDescriptionId);
        if (councils.isEmpty()) {
            log.warn("No councils resolved for conversationId={}, candidateSenderId={}, appliedPosition={}, jobDescriptionId={}",
                    conversation.getId(),
                    conversation.getCandidateSenderId(),
                    conversation.getAppliedPosition(),
                    jobDescriptionId);
        }
        return councils;
    }

    private Long resolveConversationJobDescriptionId(InterviewConversation conversation) {
        if (conversation.getJobDescriptionId() != null) {
            return conversation.getJobDescriptionId();
        }
        CandidateProfile candidateProfile =
                candidateProfileService.getPassedCandidateBySenderId(conversation.getCandidateSenderId());
        if (candidateProfile != null && candidateProfile.getJobDescriptionId() != null) {
            return candidateProfile.getJobDescriptionId();
        }
        String appliedPosition = candidateProfile != null && StringUtils.hasText(candidateProfile.getAppliedPosition())
                ? candidateProfile.getAppliedPosition()
                : conversation.getAppliedPosition();
        return findBestMatchingJobDescriptionId(appliedPosition);
    }

    private Long findBestMatchingJobDescriptionId(String appliedPosition) {
        if (!StringUtils.hasText(appliedPosition)) {
            return null;
        }

        Optional<JobDescription> exactMatch =
                jobDescriptionRepository.findFirstByTitleIgnoreCaseOrderByCreatedAtDesc(appliedPosition.trim());
        if (exactMatch.isPresent()) {
            return exactMatch.get().getId();
        }

        String normalizedTarget = normalizeLookupValue(appliedPosition);
        JobDescription bestMatch = null;
        int bestScore = 0;

        for (JobDescription jobDescription : jobDescriptionRepository.findAll()) {
            int score = scoreJobTitleMatch(normalizedTarget, normalizeLookupValue(jobDescription.getTitle()));
            if (score > bestScore
                    || (score == bestScore
                    && score > 0
                    && bestMatch != null
                    && jobDescription.getCreatedAt().isAfter(bestMatch.getCreatedAt()))) {
                bestMatch = jobDescription;
                bestScore = score;
            }
        }

        return bestMatch == null ? null : bestMatch.getId();
    }

    private int scoreJobTitleMatch(String normalizedTarget, String normalizedTitle) {
        if (!StringUtils.hasText(normalizedTarget) || !StringUtils.hasText(normalizedTitle)) {
            return 0;
        }
        if (normalizedTitle.equals(normalizedTarget)) {
            return 1_000;
        }
        if (normalizedTitle.contains(normalizedTarget) || normalizedTarget.contains(normalizedTitle)) {
            return 700 + Math.min(normalizedTarget.length(), normalizedTitle.length());
        }

        int score = 0;
        for (String token : normalizedTarget.split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            if (normalizedTitle.contains(token)) {
                score += 100;
            }
        }
        return score;
    }

    private String normalizeLookupValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace('\u0111', 'd').replace('\u0110', 'D');
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private boolean hasOtherPendingCouncilNotifications(Long conversationId) {
        return notificationRepository.findAllByConversationIdAndNotificationKindAndStatus(
                conversationId,
                HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION,
                HrInterviewNotificationStatus.PENDING
        ).stream().findAny().isPresent();
    }

    private Optional<HrInterviewNotification> findPendingCouncilNotification(InterviewConversation conversation) {
        return notificationRepository.findAllByConversationIdAndNotificationKindAndStatus(
                        conversation.getId(),
                        HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION,
                        HrInterviewNotificationStatus.PENDING
                )
                .stream()
                .findFirst();
    }

    private void cancelOtherCouncilNotifications(Long conversationId, Long acceptedNotificationId) {
        for (HrInterviewNotification notification : notificationRepository.findAllByConversationIdAndNotificationKindAndStatus(
                conversationId,
                HrInterviewNotificationKind.COUNCIL_INTERVIEW_CONFIRMATION,
                HrInterviewNotificationStatus.PENDING
        )) {
            if (!notification.getId().equals(acceptedNotificationId)) {
                notification.setStatus(HrInterviewNotificationStatus.CANCELLED);
                notification.setRespondedAt(OffsetDateTime.now());
            }
        }
    }

    private boolean isCandidateAcceptsProposedSlot(String messageText) {
        String normalized = normalize(messageText);
        if (isCandidateRejectsProposedSlot(messageText)) {
            return false;
        }
        return normalized.equals("1")
                || normalized.contains("dong y")
                || normalized.contains("duoc")
                || normalized.contains("ok")
                || normalized.contains("okay")
                || normalized.contains("xac nhan")
                || normalized.contains("confirm")
                || normalized.contains("yes");
    }

    private boolean isCandidateRejectsProposedSlot(String messageText) {
        String normalized = normalize(messageText);
        return normalized.contains("khong")
                || normalized.contains("chua duoc")
                || normalized.contains("khong duoc")
                || normalized.contains("not ok")
                || normalized.contains("no ");
    }

    private void cancelPendingNotifications(Long conversationId) {
        for (HrInterviewNotification notification :
                notificationRepository.findAllByConversationIdAndStatus(conversationId, HrInterviewNotificationStatus.PENDING)) {
            notification.setStatus(HrInterviewNotificationStatus.CANCELLED);
            notification.setRespondedAt(OffsetDateTime.now());
        }
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

    private CouncilAssignmentChoice parseCouncilAssignmentChoice(String messageText) {
        if (!StringUtils.hasText(messageText)) {
            return null;
        }

        String normalized = normalize(messageText);
        if (!looksLikeCouncilAssignment(normalized)) {
            return null;
        }

        List<String> tokens = new ArrayList<>(java.util.Arrays.asList(normalized.split(" ")));
        if (tokens.isEmpty()) {
            return null;
        }

        Long conversationId = null;
        if (tokens.get(tokens.size() - 1).matches("\\d+")) {
            conversationId = Long.parseLong(tokens.remove(tokens.size() - 1));
        }

        while (!tokens.isEmpty() && isCouncilAssignmentTrailingToken(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }

        while (!tokens.isEmpty() && isCouncilAssignmentLeadingToken(tokens.get(0))) {
            tokens.remove(0);
        }

        if (tokens.size() >= 2 && "hoi".equals(tokens.get(0)) && "dong".equals(tokens.get(1))) {
            tokens.remove(0);
            tokens.remove(0);
        } else if (!tokens.isEmpty() && "hd".equals(tokens.get(0))) {
            tokens.remove(0);
        }

        while (!tokens.isEmpty() && isCouncilAssignmentLeadingToken(tokens.get(0))) {
            tokens.remove(0);
        }
        while (!tokens.isEmpty() && isCouncilAssignmentTrailingToken(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }

        String councilReference = String.join(" ", tokens).trim();
        return StringUtils.hasText(councilReference)
                ? new CouncilAssignmentChoice(councilReference, conversationId)
                : null;
    }

    private boolean looksLikeCouncilAssignment(String normalizedMessage) {
        if (!StringUtils.hasText(normalizedMessage)) {
            return false;
        }
        return normalizedMessage.startsWith("chon ")
                || normalizedMessage.startsWith("gan ")
                || normalizedMessage.startsWith("hoi dong ")
                || normalizedMessage.startsWith("hd ")
                || normalizedMessage.contains(" vao lich ")
                || normalizedMessage.contains(" cho lich ")
                || normalizedMessage.contains(" cho ma ")
                || normalizedMessage.contains(" vao ma ");
    }

    private boolean isCouncilAssignmentLeadingToken(String token) {
        return "chon".equals(token)
                || "gan".equals(token)
                || "cho".equals(token)
                || "vao".equals(token)
                || "lich".equals(token)
                || "ma".equals(token)
                || "id".equals(token);
    }

    private boolean isCouncilAssignmentTrailingToken(String token) {
        return "cho".equals(token)
                || "vao".equals(token)
                || "lich".equals(token)
                || "ma".equals(token)
                || "id".equals(token)
                || "phong".equals(token)
                || "van".equals(token);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace('\u0111', 'd').replace('\u0110', 'D');
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private enum HrAction {
        CONFIRM,
        RESCHEDULE,
        NONE
    }

    private record CouncilAssignmentChoice(String councilReference, Long conversationId) {
    }
}
