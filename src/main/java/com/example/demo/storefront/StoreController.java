package com.example.demo.storefront;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.demo.storefront.StoreDtos.*;

@RestController
@RequestMapping("/api/store")
@RequiredArgsConstructor
public class StoreController {
 private final StoreService store;
 @GetMapping("/catalog") public Catalog catalog(){return store.catalog(false);}
 @PostMapping("/consultations") public Map<String,String> consultation(@Valid @RequestBody Consultation body){return Map.of("id",store.consultation(body));}
 @PutMapping("/me/profile") public Object profile(Authentication actor,@Valid @RequestBody Profile body){return store.profile(actor,body);}
 @GetMapping("/me/cart") public List<CartRow> cart(Authentication actor){return store.cart(actor);}
 @PostMapping("/me/cart") public List<CartRow> mergeCart(Authentication actor,@Valid @RequestBody CartMerge body){return store.mergeCart(actor,body.items());}
 @DeleteMapping("/me/cart/{courseId}") public List<CartRow> removeCart(Authentication actor,@PathVariable String courseId){return store.removeCart(actor,courseId);}
 @GetMapping("/me/enrollments") public List<Enrollment> enrollments(Authentication actor){return store.enrollments(actor);}
 @GetMapping("/me/orders") public List<Order> orders(Authentication actor){return store.orders(actor);}
 @PostMapping("/me/orders") public Order checkout(Authentication actor,@Valid @RequestBody Checkout body){return store.checkout(actor,body);}
 @PostMapping("/me/orders/{orderId}/cancel") public Order cancel(Authentication actor,@PathVariable String orderId){return store.cancel(actor,orderId);}
 @GetMapping("/admin/catalog") public Catalog adminCatalog(){return store.catalog(true);}
 @PostMapping("/admin/courses") public Object createCourse(@Valid @RequestBody CourseEdit body){return Map.of("id",store.createCourse(body));}
 @PutMapping("/admin/courses/{courseId}") public void editCourse(@PathVariable String courseId,@Valid @RequestBody CourseEdit body){store.editCourse(courseId,body);}
 @PutMapping("/admin/classes/{classId}") public void editOpening(@PathVariable String classId,@Valid @RequestBody OpeningEdit body){store.editOpening(classId,body);}
 @GetMapping("/admin/orders") public List<Order> adminOrders(){return store.adminOrders();}
 @PostMapping("/admin/orders/{orderId}/confirm") public Order confirm(Authentication actor,@PathVariable String orderId){return store.confirm(actor,orderId);}
 @PostMapping("/admin/orders/{orderId}/reject") public Order reject(@PathVariable String orderId){return store.reject(orderId);}
 @GetMapping("/admin/consultations") public Object consultations(){return store.consultations();}
}
