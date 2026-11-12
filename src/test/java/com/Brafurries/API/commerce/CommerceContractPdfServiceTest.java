package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommerceContractPdfServiceTest {
    private final CommerceContractPdfService service = new CommerceContractPdfService();

    private CommerceContractPdfService.ContractDocument draft(String text) {
        return new CommerceContractPdfService.ContractDocument(
            "Licença com royalties", "Ilustração do mascote", text,
            "Maria de Souza", "123.456.789-00", "Rua das Flores, 100",
            null, "João da Silva", "987.654.321-00",
            "Rua das Palmeiras, 200", null,
            "NONEXCLUSIVE_LICENSE", "PERCENT_GROSS",
            new BigDecimal("12.50"), null, new BigDecimal("120.00"),
            LocalDate.of(2026,11,10),null,LocalDate.of(2026,11,12)
        );
    }

    @Test
    void rendersPortugueseTermsPartiesFinancialSnapshotsAndBothSignatureLines() throws Exception {
        byte[] pdf=service.render(draft("CLÁUSULA PRIMEIRA - As partes autorizam a reprodução e venda.\n"
            + "A licença não transfere direitos morais.\n\nCLÁUSULA SEGUNDA - Vigência."));
        assertEquals("%PDF-",new String(pdf,0,5,java.nio.charset.StandardCharsets.US_ASCII));
        try (var document=Loader.loadPDF(pdf)) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("Maria de Souza"),text);
            assertTrue(text.contains("João da Silva"),text);
            assertTrue(text.contains("Ilustração do mascote"),text);
            assertTrue(text.contains("12.50% da receita bruta"),text);
            assertTrue(text.contains("CLÁUSULA PRIMEIRA"),text);
            assertTrue(text.contains("CONTRATANTE"),text);
            assertTrue(text.contains("CONTRATADO(A)"),text);
        }
    }

    @Test
    void paginatesLongContractWithoutClippingSignatureBlocks() throws Exception {
        String text=("O uso será permitido nos termos deste contrato.\n").repeat(240);
        byte[] pdf=service.render(draft(text));
        try (var document=Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages()>=3);
            String last=new PDFTextStripper() {{
                setStartPage(document.getNumberOfPages());
                setEndPage(document.getNumberOfPages());
            }}.getText(document);
            assertTrue(last.contains("Maria de Souza - CONTRATANTE"),last);
            assertTrue(last.contains("João da Silva - CONTRATADO(A)"),last);
        }
    }

    @Test
    void rejectsCharactersItCannotRenderInsteadOfChangingTheLegalText() {
        ResponseStatusException ex=assertThrows(ResponseStatusException.class,
            ()->service.render(draft("Símbolo fora da codificação 🔐.")));
        assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
    }
}
