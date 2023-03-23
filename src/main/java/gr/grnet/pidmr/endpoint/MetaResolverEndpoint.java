package gr.grnet.pidmr.endpoint;

import gr.grnet.pidmr.service.MetaresolverService;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.net.URI;

@Path("v1/resolve")
public class MetaResolverEndpoint {

    @Inject
    MetaresolverService metaresolverService;

    public MetaResolverEndpoint(MetaresolverService metaresolverService) {
        this.metaresolverService = metaresolverService;
    }

    @Tag(name = "Metaresolver")
    @org.eclipse.microprofile.openapi.annotations.Operation(
            summary = "Resolves different types of PIDs.",
            description = "This operation can be used to resolve the different types of PIDs. Using a Metaresolver, it resolves the incoming PID. " +
                    "The 301 redirect status response code" +
                    " indicates the Metaresolver URL, which resolves the PID. The Location header contains that URL.")
    @APIResponse(
            responseCode = "301",
            description = "The Metaresolver location that resolves the PID.",
            headers = @Header(name = "Location", description = "The Metaresolver location that resolves the PID.", schema = @Schema(
                    type = SchemaType.STRING,
                    example = "http://hdl.handle.net/21.T11999/METARESOLVER@ark:/13030/tf5p30086k",
                    implementation = String.class)))
    @GET
    @Path("/{pid : .+}")
    public Response resolve(@Parameter(
            description = "The PID to be resolved.",
            required = true,
            example = "ark:/13030/tf5p30086k",
            schema = @Schema(type = SchemaType.STRING))
                                @PathParam("pid") String pid) {

        var resolvable = metaresolverService.resolve(pid);

        return Response.status(Response.Status.MOVED_PERMANENTLY).header("Location", URI.create(resolvable).toString()).build();
    }
}