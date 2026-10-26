package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {}
