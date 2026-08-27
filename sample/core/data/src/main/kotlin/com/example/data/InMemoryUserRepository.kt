package com.example.data

import com.example.domain.UserRepository

/** A concrete, platform-agnostic implementation of the domain abstraction. */
public class InMemoryUserRepository : UserRepository {
    override fun currentUserName(): String = "sample-user"
}
