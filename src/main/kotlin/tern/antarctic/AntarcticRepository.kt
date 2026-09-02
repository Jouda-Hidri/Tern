package tern.antarctic

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface MessageRepository : CrudRepository<Message, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<Message>

    @Query("SELECT * FROM messages WHERE language IS NULL OR language = '' LIMIT :limit")
    fun findWithoutLanguage(@Param("limit") limit: Int): List<Message>
}
