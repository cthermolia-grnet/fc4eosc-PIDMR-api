package org.grnet.pidmr.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.grnet.pidmr.dto.Validity;
import org.grnet.pidmr.entity.Action;
import org.grnet.pidmr.entity.Provider;
import org.grnet.pidmr.exception.FailedToStartException;
import org.grnet.pidmr.mapper.ProviderMapper;
import org.grnet.pidmr.pagination.Page;
import org.grnet.pidmr.pagination.PageResource;
import org.grnet.pidmr.pagination.PageableImpl;
import org.grnet.pidmr.dto.ProviderDto;
import org.grnet.pidmr.util.Utility;
import io.quarkus.cache.CacheResult;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Named;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This ProviderService initializes the available Providers provided in the providers.conf file.
 */
@ApplicationScoped
@Named("json-provider-service")
public class ProviderService implements ProviderServiceI {

    @ConfigProperty(name = "list.providers.file")
    String providersPath;

    @ConfigProperty(name = "list.actions.file")
    String actionsPath;


    /**
     * This method is responsible for paginating the available Providers.
     * It returns from a specific page as many Providers as the client requests.
     *
     * @param page Indicates the page number.
     * @param size The total number of Providers to be retrieved.
     * @param uriInfo The current uri.
     * @return An object represents the paginated results.
     */
    public PageResource<ProviderDto> pagination(int page, int size, UriInfo uriInfo) {

        var allProviders = getProviders();

        var partition = Utility.partition(new ArrayList<>(allProviders), size);

        var providers = partition.get(page) == null ? Collections.EMPTY_LIST : partition.get(page);

        var pageable = new PageableImpl<ProviderDto>();

        pageable.list = ProviderMapper.INSTANCE.providersToDto(providers);
        pageable.index = page;
        pageable.size = size;
        pageable.count = allProviders.size();
        pageable.page = Page.of(page, size);

        return new PageResource<>(pageable, uriInfo);
    }

    /**
     * This method returns the available Providers.
     * @return A set of Providers.
     */
    @CacheResult(cacheName = "providers")
    public Set<Provider> getProviders(){

        var mapper = JsonMapper
                .builder()
                .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .build();

        return Utility.toSet(Provider.class, mapper, providersPath);
    }

    /**
     * This method returns the available Providers.
     * @return A set of Providers.
     */
    @CacheResult(cacheName = "actions")
    public Set<Action> getActions() {

        var mapper = JsonMapper
                .builder()
                .build();

        return Utility.toSet(Action.class, mapper, actionsPath);
    }

    /**
     * Each identifier should match the regular expression provided by its Provider.
     * Getting the appropriate regex corresponding to the incoming identifier,
     * this method checks whether it's valid.
     *
     * @param pid The pid to be validated.
     * @return An object representing whether a PID is valid.
     */
    @Override
    public Validity valid(String pid){

        var type = getPidType(pid);

        var candidateType = type.orElseThrow(()->new NotAcceptableException(String.format("%s doesn't belong to any of the available types.", pid)));

        var provider = getProviderByType(candidateType);

        return valid(provider, pid);
    }

    /**
     * Each identifier should match the regular expression provided by its Provider.
     * Given the type of pid, this method checks the incoming pid validity.
     * @param pid The pid to be validated.
     * @param type The type that pid belongs to.
     * @return An object representing whether a PID is valid.
     */
    @Override
    public Validity valid(String pid, String type){

        var provider = getProviderByType(type);

        if(Objects.isNull(provider)){
            throw new NotAcceptableException(String.format("This type {%s} is not supported.", type));
        }

        if(!belongsTo(pid, type)){
            throw new NotAcceptableException(String.format("%s doesn't belong to this type : %s.", pid, type));
        }

        return valid(provider, pid);
    }


    void onStart(@Observes StartupEvent ev) {

        var mapper = JsonMapper
                .builder()
                .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .build();

        // try to read the providers file. If the file cannot be read, the application cannot start.
        try {
            Utility.toSet(Provider.class, mapper, providersPath);
        } catch (Exception e) {
            throw new FailedToStartException("The file containing the Providers cannot be read.");
        }
    }

    /**
     * Each identifier should match the regular expression provided by its Provider.
     *
     * @param pid The pid to be validated.
     * @param provider The provider that pid belongs to.
     * @return An object representing whether a PID is valid.
     */
    public Validity valid(Provider provider, String pid){

        var regex = provider.getRegex();

        var valid = regex
                .stream()
                .anyMatch(pid::matches);

        var validity = new Validity();
        validity.valid = valid;
        validity.type = provider.getType();

        return validity;
    }

    public Provider getProviderByPid(String pid, String mode){


        var type = getPidType(pid);

        var candidateType = type.orElseThrow(()->new NotAcceptableException(String.format("%s doesn't belong to any of the available types.", pid)));

        var provider = getProviderByType(candidateType);

        ProviderMapper.INSTANCE.actions(provider.getActions())
                .stream()
                .filter(action->action.mode.equals(mode))
                .findAny()
                .orElseThrow(()->new BadRequestException(String.format("This mode {%s} is not supported.", mode)));

        return provider;
    }

    public Map<String, Action> actionsToMap(){

        return getActions().stream()
                .collect(Collectors.toMap(Action::getId, Function.identity()));
    }

    /**
     * This method returns the Provider that corresponds to a particular type.
     *
     * @param type The type of Provider.
     * @return The corresponding Provider.
     */

    @CacheResult(cacheName = "providersToMap")
    public Provider getProviderByType(String type){

        var map = getProviders()
                .stream()
                .collect(Collectors.toMap(Provider::getType, Function.identity()));

        return map.get(type);
    }

    /**
     * This method finds and returns the pid type. If there is no available pid type, it returns an empty Optional object.
     *
     * @param pid The incoming pid.
     * @return The pid type.
     */
    public Optional<String> getPidType(String pid){

        var providers = getProviders();

        var optionalType = providers
                .stream()
                .map(Provider::getType)
                .filter(tp->belongsTo(pid, tp))
                .findAny();

        if(optionalType.isPresent()){

            return optionalType;
        } else{

            return providers
                    .stream()
                    .filter(Provider::isCheckTypeWithRegex)
                    .filter(pr->{

                        var regex = pr.getRegex();

                        return regex
                                .stream()
                                .anyMatch(pid::matches);
                    })
                    .map(Provider::getType)
                    .findAny();
        }
    }
}