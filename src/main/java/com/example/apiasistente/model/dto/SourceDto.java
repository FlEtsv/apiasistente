package com.example.apiasistente.model.dto;

public class SourceDto {
    private Long chunkId;
    private Long documentId;
    private String documentTitle;
    private double score;
    private String snippet;

    public SourceDto() {}

    public SourceDto(Long chunkId, Long documentId, String documentTitle, double score, String snippet) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        this.score = score;
        this.snippet = snippet;
    }

    public Long getChunkId() { return chunkId; }
    public Long getDocumentId() { return documentId; }
    public String getDocumentTitle() { return documentTitle; }
    public double getScore() { return score; }
    public String getSnippet() { return snippet; }
}
