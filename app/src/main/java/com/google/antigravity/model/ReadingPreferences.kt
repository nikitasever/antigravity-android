package com.google.antigravity.model

enum class ChatTextSize(val titleRu: String, val scale: Float) {
    SMALL("Мелкий", 14f / 15f), NORMAL("Средний", 1f), LARGE("Крупный", 1.2f);
    companion object {
        fun restore(value: String?) = entries.find { it.name == value } ?: NORMAL
    }
}

enum class ResponseFont(val titleRu: String) {
    SYSTEM("Как в интерфейсе"), SERIF("С засечками");
    companion object {
        fun restore(value: String?) = entries.find { it.name == value } ?: SERIF
    }
}
