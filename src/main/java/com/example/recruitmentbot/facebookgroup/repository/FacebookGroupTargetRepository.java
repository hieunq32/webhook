package com.example.recruitmentbot.facebookgroup.repository;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupTarget;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacebookGroupTargetRepository extends JpaRepository<FacebookGroupTarget, Long> {

    List<FacebookGroupTarget> findAllByOrderByPriorityOrderAscCreatedAtAsc();

    List<FacebookGroupTarget> findAllByActiveTrueOrderByPriorityOrderAscCreatedAtAsc();

    List<FacebookGroupTarget> findAllByIdInAndActiveTrueOrderByPriorityOrderAscCreatedAtAsc(List<Long> ids);
}
