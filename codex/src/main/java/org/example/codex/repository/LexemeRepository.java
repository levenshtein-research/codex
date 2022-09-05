package org.example.codex.repository;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import org.example.codex.model.Lexeme;
import org.springframework.data.repository.query.Param;

public interface LexemeRepository extends ArangoRepository<Lexeme, String> {
    @Query("""
                  // returns all words at a Levenshtein distance maximum of @dist from @word
                  for lexeme in Lexeme
                  
                  // redundant to return same word as input
                  filter lexeme.formUtf8General not like @word
                  
                  filter levenshtein_distance(@word, lexeme.formUtf8General) <= @dist
                  
                  // return distinct since multiple lexemes can have same forms
                  return distinct lexeme.formUtf8General
                  """)
    Iterable<String> getWithLevenshtein(@Param("word") String word, @Param("dist") Integer dist);
    @Query("""
                 for lexeme in Lexeme
                     
                     // Get words matching regex
                     filter lexeme.formUtf8General like @regex
                     
                     // return distinct since multiple lexemes can have same forms
                     return distinct lexeme.formUtf8General
                  """)
    Iterable<String> getWithRegex(@Param("regex") String regex);
    @Query("""
    LET start_vertex = (
        for l in Lexeme
        filter l.formUtf8General like @word
        return l
    )
    LET meaning_tree_roots = (
    for start in start_vertex
        for v, e, p in 1..3 inbound start GRAPH LexemeMeaningRoot
        filter p.vertices[3].parentId == 0
        return p.vertices[3])
    for root in meaning_tree_roots
        for v, e, p in 0..10 outbound root GRAPH MeaningGraph
        filter v.type == @type
        filter v.internalRep != null
        return v.internalRep
""")
    Iterable<String> getMeanings(@Param("word") String word, @Param("type") Integer type);
}