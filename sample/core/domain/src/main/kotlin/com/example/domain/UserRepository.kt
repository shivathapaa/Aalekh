package com.example.domain

/** A stable abstraction the rest of the app depends on. */
public interface UserRepository {
    public fun currentUserName(): String
}

/** The states a signed-in session can be in. */
public sealed interface SessionState {
    public object SignedOut : SessionState
    public data class SignedIn(val userName: String) : SessionState
}
