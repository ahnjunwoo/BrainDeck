package com.braindeck.common

import com.fasterxml.uuid.Generators
import java.util.UUID

/** 모든 도메인 ID는 여기서 생성한다 (UUIDv7). UUID.randomUUID() 사용 금지. */
object IdGenerator {
    private val generator = Generators.timeBasedEpochGenerator() // UUIDv7
    fun newId(): UUID = generator.generate()
}
