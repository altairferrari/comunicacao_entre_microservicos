package br.com.cursoudemy.productapi.modules.product.service;

import br.com.cursoudemy.productapi.config.exception.SuccessResponse;
import br.com.cursoudemy.productapi.config.exception.ValidationException;
import br.com.cursoudemy.productapi.modules.category.model.Category;
import br.com.cursoudemy.productapi.modules.category.service.CategoryService;
import br.com.cursoudemy.productapi.modules.product.dto.*;
import br.com.cursoudemy.productapi.modules.product.model.Product;
import br.com.cursoudemy.productapi.modules.product.repository.ProductRepository;
import br.com.cursoudemy.productapi.modules.sales.client.SalesClient;
import br.com.cursoudemy.productapi.modules.sales.dto.SalesConfirmationDTO;
import br.com.cursoudemy.productapi.modules.sales.dto.SalesProductResponse;
import br.com.cursoudemy.productapi.modules.sales.enums.SalesStatus;
import br.com.cursoudemy.productapi.modules.sales.rabbitmq.SalesConfirmationSender;
import br.com.cursoudemy.productapi.modules.supplier.model.Supplier;
import br.com.cursoudemy.productapi.modules.supplier.service.SupplierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static br.com.cursoudemy.productapi.config.RequestUtil.getCurrentRequest;
import static org.springframework.util.ObjectUtils.isEmpty;

@Slf4j
@Service
public class ProductService {

    private static final Integer ZERO = 0;
    private static final String TRANSACTION_ID = "transactionid";
    private static final String SERVICE_ID = "serviceid";

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private SalesConfirmationSender salesConfirmationSender;
    @Autowired
    private SalesClient salesClient;

    public ProductResponse save(ProductRequest request) {
        validateProductDataInformed(request);
        validateCategoryAndSupplierIdInformed(request);
        Category category = categoryService.findById(request.getCategoryId());
        Supplier supplier = supplierService.findById(request.getSupplierId());
        Product product = productRepository.save(Product.of(request, supplier, category));
        return ProductResponse.of(product);
    }

    public ProductResponse update(ProductRequest request,
                                  Integer id) {
        validateProductDataInformed(request);
        validateInformedId(id);
        validateCategoryAndSupplierIdInformed(request);
        Category category = categoryService.findById(request.getCategoryId());
        Supplier supplier = supplierService.findById(request.getSupplierId());
        Product product = Product.of(request, supplier, category);
        product.setId(id);
        productRepository.save(product);
        return ProductResponse.of(product);
    }

    private void validateProductDataInformed(ProductRequest request) {
        if (isEmpty(request.getName())) {
            throw new ValidationException("The product's name was not informed.");
        }
        if (isEmpty(request.getQuantityAvailable())) {
            throw new ValidationException("The product's quantity was not informed.");
        }
        if (request.getQuantityAvailable() <= ZERO) {
            throw new ValidationException("The quantity should not be less or equal to zero.");
        }
    }

    private void validateCategoryAndSupplierIdInformed(ProductRequest request) {
        if (isEmpty(request.getCategoryId())) {
            throw new ValidationException("The category ID was not informed.");
        }
        if (isEmpty(request.getSupplierId())) {
            throw new ValidationException("The supplier ID was not informed.");
        }
    }

    public List<ProductResponse> findAll() {
        return productRepository
                .findAll()
                .stream()
                .map(ProductResponse::of)
                .collect(Collectors.toList());
    }

    public List<ProductResponse> findByName(String name) {
        if (isEmpty(name)) {
            throw new ValidationException("The product name must be informed.");
        }
        return productRepository
                .findByNameIgnoreCaseContaining(name)
                .stream()
                .map(ProductResponse::of)
                .collect(Collectors.toList());
    }

    public List<ProductResponse> findBySupplierId(Integer supplierId) {
        if (isEmpty(supplierId)) {
            throw new ValidationException("The product' supplier ID name must be informed.");
        }
        return productRepository
                .findBySupplierId(supplierId)
                .stream()
                .map(ProductResponse::of)
                .collect(Collectors.toList());
    }

    public List<ProductResponse> findByCategoryId(Integer categoryId) {
        if (isEmpty(categoryId)) {
            throw new ValidationException("The product' category ID name must be informed.");
        }
        return productRepository
                .findByCategoryId(categoryId)
                .stream()
                .map(ProductResponse::of)
                .collect(Collectors.toList());
    }

    public ProductResponse findByIdResponse(Integer id) {
        return ProductResponse.of(findById(id));
    }

    public Product findById(Integer id) {
        validateInformedId(id);
        return productRepository
                .findById(id)
                .orElseThrow(() -> new ValidationException("There's no product for the given ID."));
    }

    public Boolean existsByCategoryId(Integer categoryId) {
        return productRepository.existsByCategoryId(categoryId);
    }

    public Boolean existsBySupplierId(Integer supplierId) {
        return productRepository.existsBySupplierId(supplierId);
    }

    public SuccessResponse delete(Integer id) {
        validateInformedId(id);
        if (!productRepository.existsById(id)) {
            throw new ValidationException("The product does not exists.");
        }
        SalesProductResponse sales = getSalesByProductId(id);
        if (!isEmpty(sales.getSalesIds())) {
            throw new ValidationException("The product cannot be deleted. There are sales for it.");
        }
        productRepository.deleteById(id);
        return SuccessResponse.create("The product was deleted.");
    }

    private void validateInformedId(Integer id) {
        if (isEmpty(id)) {
            throw new ValidationException("The supplier ID must be informed.");
        }
    }

    public void updateProductStock(ProductStockDTO product) {
        try {
            validateStockUpdateData(product);
            updateStock(product);
        } catch (Exception ex) {
            log.error("Error while trying to update stock for message with error: {}", ex.getMessage(), ex);
            SalesConfirmationDTO rejectedMessage = new SalesConfirmationDTO(product.getSalesId(), SalesStatus.REJECTED, product.getTransactionid());
            salesConfirmationSender.sendSalesConfirmationMessage(rejectedMessage);
        }
    }

    @Transactional
    private void validateStockUpdateData(ProductStockDTO product) {
        if (isEmpty(product)
                || isEmpty(product.getSalesId())) {
            throw new ValidationException("The product data and the sales ID must be informed.");
        }
        if (isEmpty(product.getProducts())) {
            throw new ValidationException("The sales' products must be informed.");
        }
        product
                .getProducts()
                .forEach(salesProduct -> {
                    if (isEmpty(salesProduct.getQuantity())
                            || isEmpty(salesProduct.getProductId())) {
                        throw new ValidationException("The productID and the quantity must be informed.");
                    }
                });
    }

    @Transactional
    private void updateStock(ProductStockDTO product) {
        ArrayList<Product> productsForUpdate = new ArrayList<Product>();
        product
                .getProducts()
                .forEach(salesProduct -> {
                    Product existingProduct = findById(salesProduct.getProductId());
                    validateQuantityInStock(salesProduct, existingProduct);
                    existingProduct.updateStock(salesProduct.getQuantity());
                    productsForUpdate.add(existingProduct);
                });
        if (!isEmpty(productsForUpdate)) {
            productRepository.saveAll(productsForUpdate);
            SalesConfirmationDTO approvedMessage = new SalesConfirmationDTO(product.getSalesId(), SalesStatus.APPROVED, product.getTransactionid());
            salesConfirmationSender.sendSalesConfirmationMessage(approvedMessage);
        }
    }

    private void validateQuantityInStock(ProductQuantityDTO salesProduct,
                                         Product existingProduct) {
        if (salesProduct.getQuantity() > existingProduct.getQuantityAvailable()) {
            throw new ValidationException(
                    String.format("The product %s is out of stock.", existingProduct.getId()));
        }
    }

    public ProductSalesResponse findProductSales(Integer id) {
        Product product = findById(id);
        SalesProductResponse sales = getSalesByProductId(product.getId());
        return ProductSalesResponse.of(product, sales.getSalesIds());
    }

    private SalesProductResponse getSalesByProductId(Integer productId) {
        try {
            HttpServletRequest currentRequest = getCurrentRequest();
            String transactionid = currentRequest.getHeader(TRANSACTION_ID);
            Object serviceid = currentRequest.getAttribute(SERVICE_ID);
            log.info("Sending GET request to orders by productId with data {} | [transactionID: {} | serviceID: {}]",
                    productId, transactionid, serviceid);
            SalesProductResponse response = salesClient
                    .findSalesByProductId(productId)
                    .orElseThrow(() -> new ValidationException("The sales was not found by this product."));
            log.info("Recieving response from orders by productId with data {} | [transactionID: {} | serviceID: {}]",
                    new ObjectMapper().writeValueAsString(response), transactionid, serviceid);
            return response;
        } catch (Exception ex) {
            throw new ValidationException("The sales could not be found.");
        }
    }

    public SuccessResponse checkProductsStock(ProductCheckStockRequest request) {
        try {
            HttpServletRequest currentRequest = getCurrentRequest();
            String transactionid = currentRequest.getHeader(TRANSACTION_ID);
            Object serviceid = currentRequest.getAttribute(SERVICE_ID);
            log.info("Request to POST product stock with data {} | [transactionID: {} | serviceID: {}]",
                    new ObjectMapper().writeValueAsString(request), transactionid, serviceid);
            if (isEmpty(request) || isEmpty(request.getProducts())) {
                throw new ValidationException("The request data and products must be informed.");
            }
            request
                    .getProducts()
                    .forEach(this::validateStock);
            SuccessResponse response = SuccessResponse.create("The stock is ok!");
            log.info("Response to POST product stock with data {} | [transactionID: {} | serviceID: {}]",
                    new ObjectMapper().writeValueAsString(response), transactionid, serviceid);
            return response;
        } catch (Exception ex) {
            throw new ValidationException(ex.getMessage());
        }
    }

    private void validateStock(ProductQuantityDTO productQuantity) {
        if (isEmpty(productQuantity.getProductId()) || isEmpty(productQuantity.getQuantity())) {
            throw new ValidationException("Product ID and quantity must be informed.");
        }
        Product product = findById(productQuantity.getProductId());
        if (productQuantity.getQuantity() > product.getQuantityAvailable()) {
            throw new ValidationException(String.format("The product %s is out of stock.", product.getId()));
        }
    }
}
