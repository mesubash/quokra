package io.github.mesubash.quokra.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    @Id
    private UUID id;

    private String slug;

    private String displayName;

    private String publishableKey;

    private List<String> widgetOrigins;

    private List<String> consoleOrigins;

    private String logoUrl;

    private String primaryColor;

    private String botConfig;

    // Customers and agents may send images (and only while the file store works, see StorageCheck).
    private boolean attachmentsEnabled;

    // The bot as edited in the Admin tab (JSON); null means the bot file above is used.
    private String botCustom;

    // Null means always open.
    @Convert(converter = OpeningHoursConverter.class)
    private OpeningHours openingHours;

    public void useBot(String json) {
        this.botCustom = json;
    }

    public boolean isOpen(Instant at) {
        return openingHours == null || openingHours.isOpen(at);
    }

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
