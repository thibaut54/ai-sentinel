package pro.softcom.aisentinel.infrastructure.database.adapter.out.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "scannable_database_content")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseContentEntity {
    @Id
    private String id;
    private String content;
    private String title;
    private String sourceId;
}
