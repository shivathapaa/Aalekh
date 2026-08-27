package com.example.app

import com.example.data.InMemoryUserRepository
import com.example.login.LoginViewModel

/** The concrete top of the graph: wires the modules together. */
public class App {
    private val loginViewModel = LoginViewModel(InMemoryUserRepository())

    public fun start(): String = loginViewModel.signIn().toString()
}
