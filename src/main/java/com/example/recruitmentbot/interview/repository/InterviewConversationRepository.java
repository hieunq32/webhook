package com.example.recruitmentbot.interview.repository;

import com.example.recruitmentbot.interview.domain.InterviewConversation;
import com.example.recruitmentbot.interview.domain.InterviewConversationState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewConversationRepository extends JpaRepository<InterviewConversation, Long> {

    Optional<InterviewConversation> findFirstByCandidateSenderIdOrderByUpdatedAtDesc(String candidateSenderId);

    List<InterviewConversation> findAllByCandidateSenderIdOrderByUpdatedAtDesc(String candidateSenderId);

    Optional<InterviewConversation> findFirstByCandidateSenderIdAndStateInOrderByUpdatedAtDesc(
            String candidateSenderId,
            Collection<InterviewConversationState> states
    );

    List<InterviewConversation> findAllByStateIn(Collection<InterviewConversationState> states);

    Optional<InterviewConversation> findFirstByCandidateSenderIdAndStateOrderByUpdatedAtDesc(
            String candidateSenderId,
            InterviewConversationState state
    );

    List<InterviewConversation> findAllBySelectedCouncilSenderIdAndStateOrderByUpdatedAtDesc(
            String selectedCouncilSenderId,
            InterviewConversationState state
    );
}
