/*
 * #%L
 * ACS AEM Commons Bundle
 * %%
 * Copyright (C) 2020 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.adobe.acs.commons.wcm.impl;

import com.day.cq.commons.Externalizer;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageFilter;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.PageManagerFactory;
import com.day.text.Text;
import com.google.common.io.ByteStreams;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component(service = Servlet.class, property = {
        "sling.servlet.selectors=robots",
        "sling.servlet.extensions=txt",
        "sling.servlet.methods=GET"
}, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = RobotsServlet.RobotsServletConfig.class, factory = true)
public final class RobotsServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    
    private static final Logger log = LoggerFactory.getLogger(RobotsServlet.class);
    private static final String ALLOW = "Allow: ";
    private static final String USER_AGENT = "User-agent: ";
    private static final String SITEMAP = "Sitemap: ";
    private static final String DISALLOW = "Disallow: ";
    private static final String CRAWL_DELAY = "Crawl-delay: ";

    private String externalizerDomain;

    private String robotsContentsPropertyPath;

    private boolean printGroupingComments;

    private int crawlDelay;

    private transient RobotsRuleSet rules;

    @Reference
    private transient Externalizer externalizer;

    @Reference
    private transient PageManagerFactory pageManagerFactory;

    @Activate
    protected void activate(RobotsServletConfig config) {
        externalizerDomain = config.externalizer_domain();
        robotsContentsPropertyPath = config.robots_content_property_path();
        printGroupingComments = config.print_grouping_comments();
        crawlDelay = config.crawl_delay();

        rules = new RobotsRuleSet(config);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");

        write(request, response);
    }

    private void write(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (StringUtils.isNotBlank(robotsContentsPropertyPath)) {
            writeFromBinaryProperty(request, response);
        } else {
            writeFromOsgiConfig(request, response);
        }
    }

    private void writeFromOsgiConfig(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        PrintWriter writer = response.getWriter();

        PageManager pageManager = pageManagerFactory.getPageManager(request.getResourceResolver());
        Page page = pageManager.getContainingPage(request.getResource());
        if (page != null) {

            addCrawlDelay(writer);

            rules.getGroups().forEach(group -> writeGroup(group, request.getResourceResolver(), page, writer));

            rules.getSitemaps().stream().map(sitemap -> buildSitemapDirective(sitemap, request, pageManager, request.getResourceResolver())).forEach(writer::println);
            if (!rules.getSitemapProperties().isEmpty()) {
                addRuleForPageHavingBooleanProperty(page, rules.getSitemapProperties(), writer, 
                                                    (currentPage) -> buildSitemapDirective(currentPage.getPath(), request, pageManager, request.getResourceResolver()), false);
            }
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void writeGroup(RobotsRuleGroup group, ResourceResolver resourceResolver, Page page, PrintWriter writer) {
        if (printGroupingComments) {
            writer.println("# Start Group: " + group.getGroupName());
        }

        PageManager pageManager = page.getPageManager();
        group.getUserAgents().stream().map(this::buildUserAgentsDirective).forEach(writer::println);

        group.getAllowed().stream().map(allowed -> buildAllowedOrDisallowedDirective(true, allowed, pageManager, resourceResolver, false)).forEach(writer::println);

        List<String> allowPageProperties = group.getAllowPageProperties();
        if (!allowPageProperties.isEmpty()) {
            addRuleForPageHavingBooleanProperty(page, allowPageProperties, writer, (currentPage) -> buildAllowedOrDisallowedDirective(true, currentPage.getPath(), pageManager, resourceResolver, true), false);
        }

        List<String> allowPathProperties = group.getAllowPathProperties();
        if (!allowPathProperties.isEmpty()) {
            addRuleForPageHavingBooleanProperty(page, allowPathProperties, writer, (currentPage) -> buildAllowedOrDisallowedDirective(true, currentPage.getPath(), pageManager, resourceResolver, false), true);
        }

        group.getDisallowed().stream().map(disallowed -> buildAllowedOrDisallowedDirective(false, disallowed, page.getPageManager(), resourceResolver, false)).forEach(writer::println);

        List<String> disallowPageProperties = group.getDisallowPageProperties();
        if (!disallowPageProperties.isEmpty()) {
            addRuleForPageHavingBooleanProperty(page, disallowPageProperties, writer,  (currentPage) -> buildAllowedOrDisallowedDirective(false, currentPage.getPath(), pageManager, resourceResolver, true), false);
        }

        List<String> disallowPathProperties = group.getDisallowPathProperties();
        if (!disallowPathProperties.isEmpty()) {
            addRuleForPageHavingBooleanProperty(page, disallowPathProperties, writer,  (currentPage) -> buildAllowedOrDisallowedDirective(false, currentPage.getPath(), pageManager, resourceResolver, false), true);
        }

        if (printGroupingComments) {
            writer.println("# End Group: " + group.getGroupName());
        }
    }

    /**
     * Recursively calls {@code ruleBuilderFunc} for the current page and all its children where one of the given propNames is having the boolean value {@code true}
     * @param page the page from which to start
     * @param propNames the list of property names on the page to evaluate
     * @param writer contains the generated output
     * @param ruleBuilderFunc generates the rule which is added to {@code writer}
     * @param doNotRecurseIfRuleAdded set to false if generating a rule for path X should NOT prevent the recursion
     *                                into subpaths of X.
     */
    private void addRuleForPageHavingBooleanProperty(Page page, List<String> propNames, PrintWriter writer, Function<Page, String> ruleBuilderFunc, boolean doNotRecurseIfRuleAdded) {
        ValueMap pageProps = page.getProperties();
        boolean added = false;
        for (String prop : propNames) {
            boolean shouldAdd = pageProps.get(prop, false);
            if (shouldAdd) {
                String rule = ruleBuilderFunc.apply(page);
                writer.println(rule);
                added = true;
                break;
            }
        }

        // Don't recurse into nested paths (stop here) if a rule has been added for this path AND this type of rule
        // prevents recursing.
        // Applies to path-based rules: There is no point in declaring ALLOW <subpath> if ALLOW <path> is declared.
        // Same goes for DISALLOW <subpath>
        // But for single page rules (ALLOW <page>), the path tree must be walked
        if (!(added && doNotRecurseIfRuleAdded)) {
            Iterator<Page> pageIterator = page.listChildren(new PageFilter(false, true), false);
            pageIterator.forEachRemaining(child -> addRuleForPageHavingBooleanProperty(child, propNames, writer, ruleBuilderFunc, doNotRecurseIfRuleAdded));
        }

    }

    private void writeFromBinaryProperty(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String absoluteRobotsContentsPropertyPath = robotsContentsPropertyPath;
        if (!absoluteRobotsContentsPropertyPath.startsWith("/")) {
            absoluteRobotsContentsPropertyPath = request.getResource().getPath() + "/" + robotsContentsPropertyPath;
        }

        boolean written = false;
        // separate resource path from property path
        int separator = absoluteRobotsContentsPropertyPath.lastIndexOf('/');
        if (separator == -1 || separator == absoluteRobotsContentsPropertyPath.length()-1) {
            log.warn("no '/' separator found in configured property path or it ends with a separator : {}.", absoluteRobotsContentsPropertyPath);
        } else {
            String resourcePath = absoluteRobotsContentsPropertyPath.substring(0, separator);
            String propertyName = absoluteRobotsContentsPropertyPath.substring(separator+1);
            Resource resource = request.getResourceResolver().getResource(resourcePath);
            if (resource != null) {
                Object property = resource.getValueMap().get(propertyName);
                if (property != null) {
                    if (property instanceof String) {
                        response.getWriter().print(String.class.cast(property));
                        written = true;
                    } else if (property instanceof InputStream) {
                        try (InputStream stream = (InputStream)property) {
                            ByteStreams.copy(stream, response.getOutputStream());
                            written = true;
                        }
                    } else {
                        log.warn("configured property {} found, but type {} is not String or Binary.", absoluteRobotsContentsPropertyPath, property.getClass());
                    }
                } else {
                    log.warn("configured property {} does not exist below {}.", propertyName, resourcePath);
                }
            } else {
                log.warn("configured resource {} does not exist.", resourcePath);
            }
        }
        if (!written) {
            log.error("no response was written while processing robots with jcr property {}.", absoluteRobotsContentsPropertyPath);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private String buildUserAgentsDirective(String agent) {
        return USER_AGENT + agent;
    }

    private String buildCrawlerDelay() {
        if (crawlDelay > 0) {
            return CRAWL_DELAY + String.valueOf(crawlDelay);
        }
        return StringUtils.EMPTY;
    }

    private void addCrawlDelay(PrintWriter writer) {
        String crawlDelayString = buildCrawlerDelay();
        if (StringUtils.isNotBlank(crawlDelayString)) {
            writer.println(crawlDelayString);
        }
    }

    private String buildSitemapDirective(String sitemap, SlingHttpServletRequest request, PageManager pageManager, ResourceResolver resourceResolver) {
        String sitemapPagePath = sitemap;
        if (!sitemapPagePath.startsWith("/")) {
            Page currentPage = pageManager.getContainingPage(request.getResource());
            sitemapPagePath = Text.fullFilePath(currentPage.getPath(), sitemap);
        }
        Page page = pageManager.getContainingPage(sitemapPagePath);
        if (page != null) {
            log.debug("Found page at {}. Generate sitemap directive based on that.", sitemapPagePath);
            sitemap = getSitemapUrl(request, page, resourceResolver);
        }
        return SITEMAP + sitemap;
    }

    private String getSitemapUrl(SlingHttpServletRequest request, Page page, ResourceResolver resourceResolver) {
        final String sitemapRule;
        String sitemapRequestPath = page.getPath() + ".sitemap.xml";
        if (StringUtils.isNotBlank(externalizerDomain)) {
            log.debug("No externalizer domain configured, take into account current host header {} and current scheme {}", request.getServerName(), request.getScheme());
            sitemapRule = externalizer.externalLink(resourceResolver, externalizerDomain, sitemapRequestPath);
        } else {
            sitemapRule = externalizer.absoluteLink(request, request.getScheme(), sitemapRequestPath);
        }
        return sitemapRule;
    }

    /**
     * Generate an ALLOWED or DISALLOWED rule for either the a folder/path or for a single page
     * @param isAllowed
     * @param allowedOrDisallowedRule
     * @param pageManager
     * @param resourceResolver
     * @param generatePagePath false: generate rule for the path (default case before this flag was introduced).
     *                         true: generate a rule for a single page
     * @return
     */
    private String buildAllowedOrDisallowedDirective(boolean isAllowed, String allowedOrDisallowedRule, PageManager pageManager, ResourceResolver resourceResolver, boolean generatePagePath) {
        Page page = pageManager.getContainingPage(allowedOrDisallowedRule);
        if (page != null) {
            // folder path
            if (!generatePagePath) {
                allowedOrDisallowedRule = resourceResolver.map(page.getPath()) + "/";
            }
            // single page
            else {
                allowedOrDisallowedRule = resourceResolver.map(page.getPath()) + ".html";
            }

        }
        return (isAllowed ? ALLOW : DISALLOW) + allowedOrDisallowedRule;
    }

    @ObjectClassDefinition(name = "ACS AEM Commons - Robots Servlet")
    protected @interface RobotsServletConfig {

        @AttributeDefinition(name = "Web-Console Configuration Name Hint")
        String webconsole_configurationFactory_nameHint() default "Robots.txt for resource types: [{sling.servlet.resourceTypes}]";

        @AttributeDefinition(name = "Sling Resource Type", description = "Sling Resource Type for the Home Page component or components.")
        String[] sling_servlet_resourceTypes() default {};

        @AttributeDefinition(name = "Robots Content Property", description = "Path (either relative or absolute) to a String or Binary property containing the entire robots.txt contents. This could be a page property (e.g. robotsTxtContents) or the contents of a file within the DAM (e.g. /content/dam/my-site/seo/robots.txt/jcr:content/renditions/original/jcr:content/jcr:data). If this is specified, all other configurations are effectively ignored.")
        String robots_content_property_path();

        @AttributeDefinition(name = "User Agent Directives", description = "A set of User-agent directives to add to the robots file. Each directive is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<user agent name>")
        String[] user_agent_directives() default {};

        @AttributeDefinition(name = "Allow Directives", description = "A set of Allow directives to add to the robots file. Each directive is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<allowed path>. If the specified path is a valid cq page, resourceResolver.map() will be called prior to adding the rule.")
        String[] allow_directives() default {};

        @AttributeDefinition(name = "Allow Path Property Names", description = "A list of boolean page properties which enable generation of an allow directive for that path. Any directives added through this method are in addition to those specified in the allow.directives property. Each property name is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<propertyName>")
        String[] allow_property_names() default {};

        @AttributeDefinition(name = "Allow Page Property Names", description = "A list of boolean page properties which enable generation of an allow directive for that page. Any directives added through this method are in addition to those specified in the allow.directives property. Each property name is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<propertyName>")
        String[] allow_page_property_names() default {};

        @AttributeDefinition(name = "Disallow Directives", description = "A set of Disallow directives to add to the robots file. Each directive is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<disallowed path>. If the specified path is a valid cq page, resourceResolver.map() will be called prior to adding the rule.")
        String[] disallow_directives() default {};

        @AttributeDefinition(name = "Disallow Path Property Names", description = "A list of boolean page properties wich enable generation of a disallow directive for that path. Any directives added through this method are in addition to those specified in the disallowed.directives property. Each property name is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<propertyName>")
        String[] disallow_property_names() default {};

        @AttributeDefinition(name = "Disallow Page Property Names", description = "A list of boolean page properties wich enable generation of a disallow directive for that page. Any directives added through this method are in addition to those specified in the disallowed.directives property. Each property name is optionally pre-fixed with a ruleGroupName. Syntax: [<ruleGroupName>:]<propertyName>")
        String[] disallow_page_property_names() default {};

        @AttributeDefinition(name = "Sitemap Directives", description = "A set of Sitemap directives to add to the robots file. If the specified path is a valid AEM page path (either absolute or relative to the current page), externalizer is called with the specified Externalizer Domain to generate an absolute url to that page's .sitemap.xml, which will resolve to the ACS Commons Site Map Servlet.")
        String[] sitemap_directives() default {};

        @AttributeDefinition(name = "Sitemap Property Names", description = "A list of boolean page properties which enable generation of the sitemap directive for that page. Any directives added through this method are in addition to those specified in the sitemap.directives property.")
        String[] sitemap_property_names() default {};

        @AttributeDefinition(name = "Externalizer Domain", description = "Must correspond to a configuration of the Externalizer component. If blank the externalization will prepend the current request's scheme combined with the current request's host header.")
        String externalizer_domain() default "publish";

        @AttributeDefinition(name = "Print Grouping Comments", description = "When enabled, comments are printed to the file for start and end of each rule group. This is primarily for debugging purposes.")
        boolean print_grouping_comments() default false;

        @AttributeDefinition(name = "Crawl Delay", description = "Specify the crawl delay in seconds, the crawler will wait for the amount of seconds before crawling")
        int crawl_delay() default 0;
    }

    private class RobotsRuleSet {

        private static final String GROUP_NAME_DEFAULT = "RobotsServletDefaultGroup";

        private List<String> sitemaps;
        private List<String> sitemapProperties;
        private Map<String, RobotsRuleGroup> groups;

        public RobotsRuleSet(RobotsServletConfig config) {
            sitemaps = Arrays.asList(config.sitemap_directives());
            sitemapProperties = Arrays.asList(config.sitemap_property_names());
            groups = new LinkedHashMap<>();

            String[] userAgents = config.user_agent_directives();
            processConfig(userAgents, group -> group.userAgents);

            String[] allowed = config.allow_directives();
            processConfig(allowed, group -> group.allowed);

            String[] disallowed = config.disallow_directives();
            processConfig(disallowed, group -> group.disallowed);

            String[] disallowPathProps = config.disallow_property_names();
            processConfig(disallowPathProps, group -> group.disallowPathProperties);

            String[] disallowPageProps = config.disallow_page_property_names();
            processConfig(disallowPageProps, group -> group.disallowPageProperties);

            String[] allowPathProps = config.allow_property_names();
            processConfig(allowPathProps, group -> group.allowPathProperties);

            String[] allowPageProps = config.allow_page_property_names();
            processConfig(allowPageProps, group -> group.allowPageProperties);
        }

        private void processConfig(String[] configs, Function<RobotsRuleGroup, List<String>> configListGetFunc) {
            for (String config : configs) {
                Pair<String, String> groupNamePair = getGroupNameTuple(config);
                String groupName = groupNamePair.getKey();
                String value = groupNamePair.getValue();

                RobotsRuleGroup group = getRobotsRuleGroup(groupName);
                configListGetFunc.apply(group).add(value);
                groups.put(groupName, group);
            }
        }

        private RobotsRuleGroup getRobotsRuleGroup(String groupName) {
            RobotsRuleGroup group = groups.get(groupName);
            if (group == null) {
                group = new RobotsRuleGroup();
                group.groupName = groupName;
            }
            return group;
        }

        private Pair<String, String> getGroupNameTuple(String configValue) {
            String groupName = GROUP_NAME_DEFAULT;
            String value = configValue;
            String[] split = configValue.split(":");
            if (split.length > 1) {
                groupName = split[0];
                value = split[1];
            }

            return new ImmutablePair<>(groupName, value);
        }

        public List<String> getSitemaps() {
            return Collections.unmodifiableList(sitemaps);
        }

        public List<String> getSitemapProperties() {
            return Collections.unmodifiableList(sitemapProperties);
        }

        public List<RobotsRuleGroup> getGroups() {
            return new ArrayList<>(groups.values());
        }
    }

    private class RobotsRuleGroup {

        private String groupName;
        private List<String> userAgents = new ArrayList<>();
        private List<String> allowed = new ArrayList<>();
        private List<String> disallowed = new ArrayList<>();
        private List<String> allowPathProperties = new ArrayList<>();
        private List<String> allowPageProperties = new ArrayList<>();
        private List<String> disallowPathProperties = new ArrayList<>();
        private List<String> disallowPageProperties = new ArrayList<>();

        public String getGroupName() {
            return groupName;
        }

        public List<String> getUserAgents() {
            return Collections.unmodifiableList(userAgents);
        }

        public List<String> getAllowed() {
            return Collections.unmodifiableList(allowed);
        }

        public List<String> getDisallowed() {
            return Collections.unmodifiableList(disallowed);
        }

        public List<String> getAllowPathProperties() {
            return Collections.unmodifiableList(allowPathProperties);
        }

        public List<String> getAllowPageProperties() {
            return Collections.unmodifiableList(allowPageProperties);
        }

        public List<String> getDisallowPathProperties() {
            return Collections.unmodifiableList(disallowPathProperties);
        }

        public List<String> getDisallowPageProperties() {
            return Collections.unmodifiableList(disallowPageProperties);
        }
    }
}
