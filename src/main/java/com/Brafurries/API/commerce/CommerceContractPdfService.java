package com.Brafurries.API.commerce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plain-text-only, server-rendered legal document. No arbitrary HTML, scripts,
 * external image URLs or client-supplied fonts. Every rendered page is part of
 * the PDF saved privately before its identifier is returned to the client.
 */
@Service
public class CommerceContractPdfService {
    private static final PDFont REGULAR =
        new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDFont BOLD =
        new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
    private static final float MARGIN = 52f;
    private static final float BODY_SIZE = 11.5f;
    private static final float LEADING = 16f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    public record ContractDocument(
        String title, String artworkTitle, String body,
        String contractorName, String contractorDocument, String contractorAddress,
        String contractorRepresentative, String artistName, String artistDocument,
        String artistAddress, String artistRepresentative, String modality,
        String royaltyMethod, BigDecimal royaltyPercent, BigDecimal royaltyFixed,
        BigDecimal oneTimeAmount, LocalDate effectiveFrom, LocalDate effectiveUntil,
        LocalDate preparedOn
    ) {}

    public byte[] render(ContractDocument value) {
        try (PDDocument pdf = new PDDocument()) {
            Writer writer = new Writer(pdf);
            try {
                writer.heading("CONTRATO DE EXPLORAÇÃO DE OBRA ARTÍSTICA");
                writer.body("Modelo: " + value.title());
                writer.body("Obra: " + value.artworkTitle());
                writer.space();
                writer.heading("QUALIFICAÇÃO DAS PARTES");
                writer.body("CONTRATANTE: " + value.contractorName()
                    + ", documento " + value.contractorDocument()
                    + ", endereço " + value.contractorAddress() + ".");
                if (present(value.contractorRepresentative())) {
                    writer.body("Representante da contratante: " + value.contractorRepresentative() + ".");
                }
                writer.body("CONTRATADO(A) / TITULAR DA OBRA: " + value.artistName()
                    + ", documento " + value.artistDocument()
                    + ", endereço " + value.artistAddress() + ".");
                if (present(value.artistRepresentative())) {
                    writer.body("Representante do(a) contratado(a): " + value.artistRepresentative() + ".");
                }
                writer.space();
                writer.heading("CONDIÇÕES COMERCIAIS");
                writer.body("Modalidade contratual: " + labelModality(value.modality()) + ".");
                writer.body("Repasse: " + labelRoyalty(value) + ".");
                writer.body("Pagamento único previsto: " + currency(value.oneTimeAmount()) + ".");
                writer.body("Início da vigência: " + value.effectiveFrom().format(DATE)
                    + ". Término: " + (value.effectiveUntil() == null
                        ? "sem data determinada" : value.effectiveUntil().format(DATE)) + ".");
                writer.space();
                writer.heading("CLÁUSULAS, TERMOS E CONCESSÕES");
                for (String paragraph : value.body().replace("\r\n", "\n").split("\n", -1)) {
                    if (paragraph.isBlank()) writer.space();
                    else writer.body(paragraph);
                }
                writer.space();
                writer.body("As partes declaram ter lido o instrumento acima e "
                    + "formalizam a sua concordância mediante assinatura.");
                writer.space();
                writer.body("Data de emissão do documento: " + value.preparedOn().format(DATE) + ".");
                writer.space();
                writer.signatures(value.contractorName(), value.artistName());
            } finally {
                writer.close();
            }
            writer.footer();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            pdf.save(output);
            return output.toByteArray();
        } catch (IllegalArgumentException ex) {
            // Standard 14 Times supports Brazilian accents in WinAnsi, but not
            // arbitrary Unicode symbols. Never silently replace legal terms.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "O contrato contém caracteres não suportados pelo PDF. "
                + "Remova símbolos ou emojis especiais e tente novamente.", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível gerar o documento PDF", ex);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String currency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(BR).format(value);
    }

    private static String labelModality(String value) {
        return switch (value) {
            case "ONE_TIME_FEE" -> "Pagamento único";
            case "RIGHTS_ASSIGNMENT" -> "Cessão de direitos patrimoniais";
            case "EXCLUSIVE_LICENSE" -> "Licença exclusiva";
            case "NONEXCLUSIVE_LICENSE" -> "Licença não exclusiva";
            case "ROYALTY" -> "Royalties";
            case "HYBRID" -> "Pagamento e royalties (híbrido)";
            default -> throw new IllegalArgumentException("Modalidade inválida");
        };
    }

    private static String labelRoyalty(ContractDocument d) {
        return switch (d.royaltyMethod()) {
            case "NONE" -> "Sem repasse recorrente";
            case "PERCENT_GROSS" -> d.royaltyPercent().toPlainString() + "% da receita bruta";
            case "PERCENT_NET" -> d.royaltyPercent().toPlainString() + "% da receita líquida";
            case "FIXED_UNIT" -> currency(d.royaltyFixed()) + " por unidade";
            case "FIXED_MONTH" -> currency(d.royaltyFixed()) + " mensal";
            default -> throw new IllegalArgumentException("Regra de repasse inválida");
        };
    }

    private static final class Writer implements AutoCloseable {
        private final PDDocument pdf;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        private Writer(PDDocument pdf) throws IOException {
            this.pdf = pdf;
            newPage();
        }

        private void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
            pageNumber++;
        }

        private void ensure(float remaining) throws IOException {
            if (y - remaining < MARGIN + 24) newPage();
        }

        private void space() throws IOException {
            ensure(LEADING);
            y -= LEADING / 2f;
        }

        private void heading(String text) throws IOException {
            ensure(LEADING * 2);
            draw(text, BOLD, 12f);
            y -= 5f;
        }

        private void body(String paragraph) throws IOException {
            String normalized = paragraph.replace('\t', ' ').trim();
            if (normalized.isEmpty()) { space(); return; }
            for (String line : wrap(normalized, REGULAR, BODY_SIZE,
                PDRectangle.A4.getWidth() - MARGIN * 2)) {
                ensure(LEADING);
                draw(line, REGULAR, BODY_SIZE);
            }
            y -= 3f;
        }

        private void draw(String line, PDFont font, float size) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(line);
            stream.endText();
            y -= LEADING;
        }

        private List<String> wrap(String text, PDFont font, float size, float maxWidth)
            throws IOException {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" +")) {
                String proposed = line.isEmpty() ? word : line + " " + word;
                if (width(font, proposed, size) <= maxWidth) {
                    line.setLength(0);
                    line.append(proposed);
                    continue;
                }
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                for (int i = 0; i < word.length(); i++) {
                    String next = line.toString() + word.charAt(i);
                    if (!line.isEmpty() && width(font, next, size) > maxWidth) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(word.charAt(i));
                }
            }
            if (!line.isEmpty()) out.add(line.toString());
            return out;
        }

        private float width(PDFont font, String text, float fontSize) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        private void signatures(String contractor, String artist) throws IOException {
            ensure(160f); // Keep both parties on the same page.
            y -= 20f;
            body("________________________________________________");
            body(contractor + " - CONTRATANTE");
            y -= 15f;
            body("________________________________________________");
            body(artist + " - CONTRATADO(A)");
        }

        private void footer() throws IOException {
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                try (PDPageContentStream footer = new PDPageContentStream(
                    pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.beginText();
                    footer.setFont(REGULAR, 9f);
                    footer.newLineAtOffset(MARGIN, 28f);
                    footer.showText("Página " + (i + 1) + " de " + pdf.getNumberOfPages());
                    footer.endText();
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
