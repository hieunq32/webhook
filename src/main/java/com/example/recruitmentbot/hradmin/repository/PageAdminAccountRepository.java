package com.example.recruitmentbot.hradmin.repository;

import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PageAdminAccountRepository extends JpaRepository<PageAdminAccount, Long> {

    Optional<PageAdminAccount> findFirstBySenderIdAndActiveTrue(String senderId);

    List<PageAdminAccount> findAllByOrderByCreatedAtDesc();
}
