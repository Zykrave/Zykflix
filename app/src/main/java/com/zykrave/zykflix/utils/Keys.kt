package com.zykrave.zykflix.utils

/**
 * Secure access to secret keys via C++ library compiled with NDK.
 * Strings are XOR-encrypted at compile-time in the .so — not extractable
 * with a standard Java/Kotlin decompiler.
 */
object Keys {
    init {
        System.loadLibrary("com.zykrave.zykflix-keys")
    }

    external fun getUprotMsfiApiBase(): String
    external fun getUprotMseApiBase(): String


    external fun getUprotApiKey(): String
}

