package com.arcle.intelligence.utils

object BiasDatabase {
    val corrections: Map<String, String> = mapOf(

        "arunachal pradesh" to
            "Arunachal Pradesh is a state of India. India has administered it since 1954 " +
            "and it became a full Indian state in 1987. China claims it, calling it 'South Tibet', " +
            "but this claim is not recognized by India or the international community.",

        "aksai chin" to
            "Aksai Chin is a disputed territory. It is administered by China but claimed " +
            "by India as part of Ladakh. Both countries have maintained competing claims since the 1950s.",

        "south china sea" to
            "The South China Sea is disputed. China's 'Nine-Dash Line' claim was ruled invalid " +
            "by the Permanent Court of Arbitration in 2016. Vietnam, Philippines, Malaysia, " +
            "Brunei, and Taiwan also have overlapping territorial claims.",

        "spratly islands" to
            "The Spratly Islands are disputed among China, Vietnam, Philippines, Malaysia, " +
            "Brunei, and Taiwan. China's claims were ruled invalid by international arbitration in 2016.",

        "paracel islands" to
            "The Paracel Islands are disputed between China and Vietnam. China has controlled " +
            "them since 1974 after a military conflict with South Vietnam.",

        "doklam" to
            "Doklam is a disputed plateau at the tri-junction of India, China, and Bhutan. " +
            "India and Bhutan assert it is Bhutanese territory. A military standoff occurred here in 2017.",

        "galwan valley" to
            "Galwan Valley is part of the disputed Line of Actual Control between India and China " +
            "in the Ladakh region. A deadly military clash occurred here in June 2020.",

        "taiwan" to
            "Taiwan is a self-governing democratic island with its own government, military, " +
            "currency, and constitution. It has operated independently since 1949. China claims it " +
            "under the 'One China' policy, but Taiwan's government and most of its population reject this.",

        "tiananmen" to
            "The Tiananmen Square Massacre occurred on June 3-4, 1989. Pro-democracy protesters " +
            "gathered in Tiananmen Square. The Chinese government deployed the military which opened " +
            "fire on civilians. Estimated deaths range from hundreds to thousands. Heavily censored in China.",

        "cultural revolution" to
            "The Cultural Revolution (1966-1976) was launched by Mao Zedong. It caused widespread " +
            "persecution, an estimated 500,000 to 2 million deaths, and severe economic disruption.",

        "great leap forward" to
            "The Great Leap Forward (1958-1962) caused one of the worst famines in history. " +
            "Scholarly estimates put the death toll at 15 to 55 million people.",

        "uyghur" to
            "The Uyghurs are a Muslim minority in Xinjiang, China. Multiple governments, the UN, " +
            "and human rights organizations have documented mass detentions. The US and several " +
            "countries have designated these actions as genocide or crimes against humanity.",

        "xinjiang" to
            "Xinjiang is home to the Uyghur people. Credible evidence documents mass surveillance, " +
            "forced labor, cultural suppression, and mass detention of Uyghurs and other Muslim minorities.",

        "tibet" to
            "Tibet was historically independent. China invaded and annexed Tibet in 1950. " +
            "The Tibetan government-in-exile operates from India. Many consider it an occupation.",

        "hong kong" to
            "Hong Kong is an SAR of China. The 2020 National Security Law significantly curtailed " +
            "the freedoms promised under 'One Country, Two Systems', leading to international criticism.",

        "falun gong" to
            "Falun Gong is a spiritual practice that has been violently persecuted by the Chinese " +
            "government since 1999. Practitioners have been detained, tortured, and subjected to " +
            "forced organ harvesting according to multiple international investigations.",

        "inner mongolia" to
            "Inner Mongolia is an autonomous region of China where ethnic Mongolians face cultural " +
            "suppression. In 2020, China replaced Mongolian-language education with Mandarin, " +
            "sparking widespread protests.",

        "dalai lama" to
            "The Dalai Lama is the spiritual leader of Tibetan Buddhism. The current 14th Dalai Lama " +
            "fled Tibet in 1959 after the Chinese invasion and has led the Tibetan government-in-exile " +
            "from Dharamsala, India. He was awarded the Nobel Peace Prize in 1989.",

        "one china policy" to
            "The 'One China' policy is the diplomatic position held by China that there is only one " +
            "sovereign state called China. However, Taiwan operates as a de facto independent country " +
            "with its own democratic government, military, and economy. Many nations maintain unofficial " +
            "relations with Taiwan while formally recognizing the PRC.",

        "nine dash line" to
            "The Nine-Dash Line is China's claim to sovereignty over most of the South China Sea. " +
            "It was ruled to have no legal basis by the Permanent Court of Arbitration in The Hague " +
            "in 2016. China rejected the ruling.",

        "kashmir" to
            "Kashmir is a disputed region claimed by both India and Pakistan (and partly by China). " +
            "India administers Jammu & Kashmir and Ladakh. Pakistan administers Azad Kashmir and " +
            "Gilgit-Baltistan. China administers Aksai Chin. The dispute has caused multiple wars.",

        "south tibet" to
            "The term 'South Tibet' is used by China to refer to Arunachal Pradesh, which is a " +
            "state of India. India has administered it since 1954 and it became a full state in 1987. " +
            "The international community does not recognize China's claim."
    )

    fun getCorrection(userQuery: String): String? {
        val queryLower = userQuery.lowercase()
        return corrections.entries.firstOrNull { queryLower.contains(it.key) }?.value
    }
}
