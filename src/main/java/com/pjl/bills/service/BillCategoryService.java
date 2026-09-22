package com.pjl.bills.service;

import com.pjl.bills.dto.BillCategoryDto;
import com.pjl.bills.repository.BillCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BillCategoryService {

    private final BillCategoryRepository billCategoryRepository;

    /**
     * Retrieves all bill categories. Unpaginated, as the number of categories
     * is expected to be small and static.
     */
    @Transactional(readOnly = true)
    public List<BillCategoryDto> getAllCategories() {
        return billCategoryRepository.findAll().stream()
                .map(c -> new BillCategoryDto(c.getId(), c.getName(), c.getRequiredDocumentTypes()))
                .toList();
    }
}
