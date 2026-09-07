package com.app.master.service.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "expense_line_item")
public class ExpenseLineItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id")
    @JsonIgnore
    private ExpenseEntity expense;

    private String description;
    private String hsnCode;
    private Double quantity;
    private Long ratePaise;
    private Double gstRate;
    private Long lineTotalPaise;
}
