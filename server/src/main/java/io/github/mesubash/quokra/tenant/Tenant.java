package io.github.mesubash.quokra.tenant;

import jakarta.persistence.Column;
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

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
