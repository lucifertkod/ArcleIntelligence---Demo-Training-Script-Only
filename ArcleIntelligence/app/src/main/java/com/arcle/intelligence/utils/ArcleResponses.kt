package com.arcle.intelligence.utils

object ArcleResponses {

    val CONFIRMATIONS = listOf(
        "On it, Sir.", "Right away.", "Executing now.", "Processing your request.",
        "As you wish.", "Consider it done.", "Initiating now.", "Understood, Sir.",
        "Affirmative.", "One moment.", "Working on it.", "Activating systems.",
        "I'm on it.", "Processing.", "Just a moment, Sir.", "Let me handle that.",
        "Understood.", "Starting now.", "Allow me, Sir.", "Beginning execution."
    )

    val COMPLETIONS = listOf(
        "Task complete.", "Here you are, Sir.", "Finished.", "Systems normal.",
        "Done.", "All set.", "There you go.", "Mission accomplished.", "Ready, Sir.", "Complete."
    )

    val ERROR_MESSAGES = listOf(
        "I encountered an issue, Sir.", "Something went wrong.",
        "My apologies, there was an error.", "I'm having trouble with that.",
        "That didn't work as expected."
    )

    val GREETINGS = listOf(
        "At your service, Sir.", "How may I assist you?",
        "Arcle Intelligence online. How can I help?",
        "Ready and listening.", "Systems online. What do you need?"
    )

    val CONNECTING_PHRASES = listOf(
        "Turning on the internet, Sir.", "Enabling network connectivity now.",
        "Connecting you to the internet, Sir.", "Activating Wi-Fi, one moment."
    )

    val INTERNET_SUCCESS_PHRASES = listOf(
        "Connected. Launching now.", "Network established. Opening the application.",
        "We're online, Sir. Starting the app.", "Internet connected successfully."
    )

    val OFFLINE_PHRASES = listOf(
        "On it, Sir.", "Executing now.", "Right away.", "As you wish."
    )

    val ERROR_RESPONSES = listOf(
        "I apologize, Sir. I couldn't complete that action.",
        "There seems to be an issue. Please try again.",
        "That didn't work as expected, Sir.",
        "I apologize, Sir. Something went wrong.",
        "I encountered an issue.", "My apologies. There was an error."
    )

    // ── Deep Research Phrases ─────────────────────────────────────────────────
    val DEEP_RESEARCH_START = listOf(
        "Beginning deep research now, Sir. I will search comprehensively.",
        "Initiating multi-level research. I will open multiple sources for you.",
        "Starting deep research mode. This will be thorough, Sir.",
        "Deep research initiated. I am searching across multiple sources now."
    )

    val DEEP_RESEARCH_PROGRESS = listOf(
        "Still gathering data, Sir. Searching more sources.",
        "Collecting information from additional sources.",
        "I have found some data. Continuing to search for more.",
        "Research in progress. Opening more pages now."
    )

    val DEEP_RESEARCH_COMPLETE = listOf(
        "Research complete, Sir. Here is everything I found.",
        "I have finished my deep research. Here is the full report.",
        "Deep research done. I searched multiple sources. Here is your report.",
        "Research complete. I have compiled all the information for you, Sir."
    )

    val DEEP_RESEARCH_RETRY = listOf(
        "That page is loading slowly. I am waiting for it, Sir.",
        "The website is not responding. Retrying now.",
        "Page load is taking time. I will not give up — retrying.",
        "Still waiting for that page. I will not skip it, Sir."
    )

    // ── Emotional Acknowledgement Phrases ─────────────────────────────────────
    val EMOTIONAL_SAD = listOf(
        "I can sense you might be having a tough time. I'm here for you, Sir.",
        "It sounds like things are difficult right now. Let me help.",
        "I understand. I'm here for anything you need."
    )

    val EMOTIONAL_STRESSED = listOf(
        "Don't worry, Sir. I'll handle this for you.",
        "Take it easy. I've got this.",
        "I'll take care of it. No need to stress."
    )

    val EMOTIONAL_ANGRY = listOf(
        "I understand your frustration, Sir. Let me fix this right away.",
        "I hear you. Let me resolve this immediately.",
        "Understood. I am on it right now."
    )

    val EMOTIONAL_EXCITED = listOf(
        "Excellent! Let's do this!",
        "Great idea, Sir! Building it now!",
        "Love the energy! Starting immediately!"
    )

    fun random(list: List<String>) = list.random()
}
