package com.example.demo.controller;

import com.example.demo.dto.PayrollDtos;
import com.example.demo.service.PayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/payroll")
public class PayrollController {
    private final PayrollService payroll;
    @GetMapping public List<Map<String,Object>> list(@RequestParam String period,@RequestParam(required=false) String employeeId){return payroll.payrolls(period,employeeId);}
    @PostMapping("/calculate") public List<Map<String,Object>> calculate(@RequestParam String period,@RequestParam(required=false) String employeeId){return payroll.calculate(period,employeeId);}
    @GetMapping("/{employeeId}/{period}") public Map<String,Object> detail(@PathVariable String employeeId,@PathVariable String period){return payroll.payrollDetail(employeeId,period);}
    @GetMapping("/{employeeId}/history") public List<Map<String,Object>> history(@PathVariable String employeeId){return payroll.history(employeeId);}
    @GetMapping("/employees/{employeeId}/salary-profile") public Map<String,Object> salaryProfile(@PathVariable String employeeId){return payroll.salaryProfile(employeeId);}
    @GetMapping("/ranks") public List<Map<String,Object>> ranks(@RequestParam(required=false) String roleId){return payroll.ranks(roleId);}
    @PostMapping("/ranks") public Map<String,Object> createRank(@Valid @RequestBody PayrollDtos.SalaryRankRequest r){return payroll.createRank(r);}
    @PutMapping("/ranks/{id}") public Map<String,Object> updateRank(@PathVariable String id,@Valid @RequestBody PayrollDtos.SalaryRankUpdate r){return payroll.updateRank(id,r);}
    @PutMapping("/rank-assignments/{employeeId}") public List<Map<String,Object>> assignRank(@PathVariable String employeeId,@Valid @RequestBody PayrollDtos.AssignSalaryRank r){return payroll.assignRank(employeeId,r);}
    @PostMapping("/reviews") public Map<String,Object> createReview(@Valid @RequestBody PayrollDtos.ReviewRequest r){return payroll.createReview(r);}
    @GetMapping("/reviews") public List<Map<String,Object>> reviews(@RequestParam(required=false) String status){return payroll.reviews(status);}
    @PostMapping("/reviews/{id}/decision") public Map<String,Object> decide(@PathVariable String id,@Valid @RequestBody PayrollDtos.ReviewDecision r){return payroll.decideReview(id,r);}
}
