package codingdojo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CustomerSync {

    private final CustomerDataAccess customerDataAccess;

    public CustomerSync(CustomerDataLayer customerDataLayer) {
        this(new CustomerDataAccess(customerDataLayer));
    }

    public CustomerSync(CustomerDataAccess db) {
        this.customerDataAccess = db;
    }

    public boolean syncWithDataLayer(ExternalCustomer externalCustomer) {
        return getCustomerSynchronizer(externalCustomer).getOrCreateCustomer()
                                                        .populateFields()
                                                        .persist()
                                                        .updateDuplicates()
                                                        .updateRelations()
                                                        .isCreated();
    }

    private CustomerSynchronizer getCustomerSynchronizer(ExternalCustomer externalCustomer) {
        return externalCustomer.isCompany() ? new CompanyCustomerTypeSync(externalCustomer) : new PersonCustomerTypeSync(externalCustomer);
    }

    public CustomerMatches loadCompany(ExternalCustomer externalCustomer) {

        final String externalId = externalCustomer.getExternalId();
        final String companyNumber = externalCustomer.getCompanyNumber();

        CustomerMatches customerMatches = customerDataAccess.loadCompanyCustomer(externalId, companyNumber);
        Customer customer = customerMatches.getCustomer();

        if (Objects.nonNull(customer)) {
            if (!CustomerType.COMPANY.equals(customer.getCustomerType())) {
                throw new ConflictException("Existing customer for externalCustomer " + externalId + " already exists and is not a company");
            }

            if ("ExternalId".equals(customerMatches.getMatchTerm())) {
                String customerCompanyNumber = customer.getCompanyNumber();
                if (!companyNumber.equals(customerCompanyNumber)) {
                    customer.setMasterExternalId(null);
                    customerMatches.addDuplicate(customerMatches.getCustomer());
                    customerMatches.setCustomer(null);
                    customerMatches.setMatchTerm(null);
                }
            } else if ("CompanyNumber".equals(customerMatches.getMatchTerm())) {
                String customerExternalId = customer.getExternalId();
                if (customerExternalId != null && !externalId.equals(customerExternalId)) {
                    throw new ConflictException("Existing customer for externalCustomer " + companyNumber + " doesn't match external id " + externalId + " instead found " + customerExternalId);
                }
                customer.setExternalId(externalId);
                customer.setMasterExternalId(externalId);
                customerMatches.addDuplicate(null);
            }
        }

        return customerMatches;
    }

    public CustomerMatches loadPerson(ExternalCustomer externalCustomer) {
        final String externalId = externalCustomer.getExternalId();

        CustomerMatches customerMatches = customerDataAccess.loadPersonCustomer(externalId);
        Customer customer = customerMatches.getCustomer();

        if (Objects.nonNull(customer)) {
            if (!CustomerType.PERSON.equals(customer.getCustomerType())) {
                throw new ConflictException("Existing customer for externalCustomer " + externalId + " already exists and is not a person");
            }
        }

        return customerMatches;
    }

    private interface CustomerSynchronizer {
        CustomerOperations getOrCreateCustomer();
    }

    private interface CustomerOperations {
        CustomerOperations persist();

        boolean isCreated();

        CustomerOperations populateFields();

        CustomerOperations updateDuplicates();

        CustomerOperations updateRelations();
    }

    private class GenericCustomerSync implements CustomerSynchronizer, CustomerOperations {
        protected final CustomerDataAccess customerDataAccess;
        protected final ExternalCustomer externalCustomer;
        protected final CustomerMatches customerMatches;
        protected final CustomerType customerType;
        protected Customer customer;
        private boolean created;


        public GenericCustomerSync(ExternalCustomer externalCustomer, CustomerType type, CustomerMatches customerMatches) {
            this.customerDataAccess = CustomerSync.this.customerDataAccess;
            this.externalCustomer = Objects.requireNonNull(externalCustomer, "externalCustomer cannot be null");
            this.customerType = Objects.requireNonNull(type, "type cannot be null");
            this.customerMatches = Objects.requireNonNull(customerMatches, "customerMatches supplier cannot be null");
        }

        @Override
        public CustomerOperations getOrCreateCustomer() {
            customer = Optional.ofNullable(customerMatches.getCustomer())
                               .orElseGet(() -> {
                                   created = true;
                                   return createCustomerWithMasterAndExternalIds(externalCustomer.getExternalId());
                               });
            return this;
        }

        @Override
        public boolean isCreated() {
            return this.created;
        }

        @Override
        public CustomerOperations persist() {
            this.customer = doPersist(customer);
            return this;
        }

        @Override
        public CustomerOperations populateFields() {
            customer.setName(externalCustomer.getName());
            customer.setCustomerType(customerType);
            customer.setAddress(externalCustomer.getPostalAddress());
            customer.setPreferredStore(externalCustomer.getPreferredStore());
            return this;
        }

        @Override
        public CustomerOperations updateDuplicates() {
            if (customerMatches.hasDuplicates()) {
                for (Customer duplicate : customerMatches.getDuplicates()) {
                    duplicate = Optional.ofNullable(duplicate)
                                        .orElseGet(() -> createCustomerWithMasterAndExternalIds(externalCustomer.getExternalId()));
                    updateDuplicateData(duplicate);
                    doPersist(duplicate);
                }
            }
            return this;
        }

        @Override
        public CustomerOperations updateRelations() {
            List<ShoppingList> consumerShoppingLists = externalCustomer.getShoppingLists();
            for (ShoppingList consumerShoppingList : consumerShoppingLists) {
                this.customerDataAccess.updateShoppingList(customer, consumerShoppingList);
            }
            return this;
        }

        protected void updateDuplicateData(Customer duplicate) {
            duplicate.setName(customer.getName());
        }

        protected Customer doPersist(Customer customer) {
            if (isCreated(customer)) {
                return this.customerDataAccess.createCustomerRecord(customer);
            } else {
                return this.customerDataAccess.updateCustomerRecord(customer);
            }
        }

        protected boolean isCreated(Customer customer) {
            return Objects.nonNull(customer) && Objects.isNull(customer.getInternalId());
        }

        protected Customer createCustomerWithMasterAndExternalIds(String externalId) {
            Customer customer = new Customer();
            customer.setExternalId(externalId);
            customer.setMasterExternalId(externalId);
            return customer;
        }
    }

    private class PersonCustomerTypeSync extends GenericCustomerSync implements CustomerSynchronizer, CustomerOperations {

        public PersonCustomerTypeSync(ExternalCustomer externalCustomer) {
            super(externalCustomer, CustomerType.PERSON, loadPerson(externalCustomer));
        }

        @Override
        public CustomerOperations populateFields() {
            super.populateFields();
            customer.setBonusPoints(externalCustomer.getBonusPoints());
            return this;
        }
    }

    private class CompanyCustomerTypeSync extends GenericCustomerSync implements CustomerSynchronizer, CustomerOperations {

        public CompanyCustomerTypeSync(ExternalCustomer externalCustomer) {
            super(externalCustomer, CustomerType.COMPANY, loadCompany(externalCustomer));
        }

        @Override
        public CustomerOperations populateFields() {
            super.populateFields();
            customer.setCompanyNumber(externalCustomer.getCompanyNumber());
            return this;
        }
    }
}
