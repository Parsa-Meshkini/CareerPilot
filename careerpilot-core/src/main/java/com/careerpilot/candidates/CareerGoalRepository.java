package com.careerpilot.candidates;

import com.careerpilot.users.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CareerGoalRepository extends JpaRepository<CareerGoal, UUID> {
    Optional<CareerGoal> findByUser(User user);
}
