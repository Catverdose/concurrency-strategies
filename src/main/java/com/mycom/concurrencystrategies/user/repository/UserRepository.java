package com.mycom.concurrencystrategies.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.concurrencystrategies.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
