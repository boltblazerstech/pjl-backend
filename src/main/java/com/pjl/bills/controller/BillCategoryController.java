package com.pjl.bills.controller;

import com.pjl.bills.dto.BillCategoryDto;
import com.pjl.bills.service.BillCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bill-categories")
@RequiredArgsConstructor
public class BillCategoryController {

    private final BillCategoryService billCategoryService;

    /**
     * Fetches all registered bill categories, returning their id, name, and 
     * required document types. Used by the frontend to populate upload forms.
     */
    @GetMapping
    public ResponseEntity<List<BillCategoryDto>> getBillCategories() {
        return ResponseEntity.ok(billCategoryService.getAllCategories());
    }
}
