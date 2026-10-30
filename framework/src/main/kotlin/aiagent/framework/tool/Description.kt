package aiagent.framework.tool

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/** 툴 인자 필드의 설명이다. 직렬화 설명자에 남아서 스키마의 `description`이 된다. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Description(val value: String)
