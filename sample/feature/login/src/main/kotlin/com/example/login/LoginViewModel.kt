package com.example.login

import com.example.domain.SessionState
import com.example.domain.UserRepository

/** A concrete feature component wiring the domain abstraction into the UI layer. */
public class LoginViewModel(private val users: UserRepository) {
    public fun signIn(): SessionState = SessionState.SignedIn(users.currentUserName())
}
