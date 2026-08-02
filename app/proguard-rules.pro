# App-specific R8 rules. Deliberately empty so far: Compose, Hilt, DataStore and the coroutines
# runtime all ship consumer keep rules of their own, and this app does no reflection of its own —
# the protocol module is plain Kotlin called directly. Add rules here only with a comment saying
# which reflective access they protect.
