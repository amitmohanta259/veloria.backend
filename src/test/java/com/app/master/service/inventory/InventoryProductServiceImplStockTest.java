package com.app.master.service.inventory;

import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.InventoryProductSizeStockRepository;
import com.app.master.service.service.admin.impl.InventoryProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins {@code addStock} to the atomic shape.
 *
 * The value of these tests is what they forbid: the service must never read a
 * stock figure, add to it in Java and write the total back. If someone
 * reintroduces that, {@code save(...)} of a stock row appears and these fail.
 */
class InventoryProductServiceImplStockTest {

    private static final UUID PRODUCT_UUID = UUID.randomUUID();
    private static final Long PRODUCT_ID = 7L;

    private InventoryProductRepository productRepository;
    private InventoryProductSizeStockRepository sizeStockRepository;
    private InventoryProductServiceImpl service;

    @BeforeEach
    void setUp() {
        productRepository = mock(InventoryProductRepository.class);
        sizeStockRepository = mock(InventoryProductSizeStockRepository.class);

        service = mock(InventoryProductServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "productRepository", productRepository);
        ReflectionTestUtils.setField(service, "sizeStockRepository", sizeStockRepository);

        when(productRepository.findByUuid(PRODUCT_UUID)).thenReturn(Optional.of(
                InventoryProductEntity.builder().id(PRODUCT_ID).initialStock(100L).build()));
    }

    @Test
    @DisplayName("A sizeless addition is a single atomic increment, not a read and a write")
    void sizelessAdditionIsAtomic() throws Exception {
        service.addStock(PRODUCT_UUID, null, 10L);

        verify(productRepository).addStockToProduct(PRODUCT_ID, 10L);
        verify(productRepository, never()).save(any());
        verify(sizeStockRepository, never()).save(any());
    }

    @Test
    @DisplayName("A sized addition increments the size row and the product total by the same delta, both atomically")
    void sizedAdditionIsAtomicAndRollsUpByDelta() throws Exception {
        when(sizeStockRepository.addStockToSize(PRODUCT_ID, "M", 10L)).thenReturn(1);

        service.addStock(PRODUCT_UUID, " M ", 10L);

        verify(sizeStockRepository).addStockToSize(PRODUCT_ID, "M", 10L);
        // The total moves by the delta. Recomputing it as SUM(size rows) reads a
        // snapshot that can predate a concurrent addition to another size, which
        // is how the roll-up lost an increment.
        verify(productRepository).addStockToProduct(PRODUCT_ID, 10L);
        verify(productRepository, never()).save(any());
        verify(sizeStockRepository, never()).findByProductIdAndArchiveFalseOrderByIdAsc(any());
    }

    @Test
    @DisplayName("A size with no row yet is created, then the total is re-derived")
    void unknownSizeCreatesTheRow() throws Exception {
        when(sizeStockRepository.addStockToSize(PRODUCT_ID, "XL", 5L)).thenReturn(0);

        service.addStock(PRODUCT_UUID, "XL", 5L);

        verify(sizeStockRepository).save(argThat(e ->
                PRODUCT_ID.equals(e.getProductId()) && "XL".equals(e.getSize()) && e.getInitialStock() == 5L));
        verify(productRepository).addStockToProduct(PRODUCT_ID, 5L);
    }

    @Test
    @DisplayName("Non-positive quantities are refused before anything is written")
    void nonPositiveQuantityIsRefused() {
        for (long qty : new long[]{0L, -1L}) {
            VeloriaException e = assertThrows(VeloriaException.class,
                    () -> service.addStock(PRODUCT_UUID, "M", qty), "qty " + qty);
            assertEquals(ResponseCode.BAD_REQUEST, e.getErrorCode());
        }
        verifyNoInteractions(sizeStockRepository);
        verify(productRepository, never()).addStockToProduct(any(), anyLong());
    }

    @Test
    @DisplayName("An unknown product is refused and writes nothing")
    void unknownProductWritesNothing() {
        UUID missing = UUID.randomUUID();
        when(productRepository.findByUuid(missing)).thenReturn(Optional.empty());

        assertThrows(VeloriaException.class, () -> service.addStock(missing, "M", 5L));
        verifyNoInteractions(sizeStockRepository);
        verify(productRepository, never()).addStockToProduct(any(), anyLong());
    }

    @Test
    @DisplayName("addStock declares a transaction, so the size row and the roll-up cannot diverge")
    void addStockIsTransactional() throws NoSuchMethodException {
        Method m = InventoryProductServiceImpl.class.getMethod("addStock", UUID.class, String.class, long.class);
        assertNotNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "addStock must be @Transactional");
    }
}
