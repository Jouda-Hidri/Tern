package tern.antarctic

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface MessageRepository : CrudRepository<Message, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<Message>

    @Modifying
    @Query("UPDATE messages SET language = :language WHERE id = :id::uuid")
    fun updateLanguage(@Param("id") id: String, @Param("language") language: String): Boolean
}
