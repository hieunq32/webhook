package com.example.recruitmentbot.facebookgroup.repository;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacebookGroupTargetRepository extends JpaRepository<FacebookGroupTarget, Long> {

    List<FacebookGroupTarget> findAllByOrderByPriorityOrderAscCreatedAtAsc();

    List<FacebookGroupTarget> findAllByActiveTrueOrderByPriorityOrderAscCreatedAtAsc();

    List<FacebookGroupTarget> findAllByIdInAndActiveTrueOrderByPriorityOrderAscCreatedAtAsc(List<Long> ids);

    Optional<FacebookGroupTarget> findByGroupReference(String groupReference);
}
