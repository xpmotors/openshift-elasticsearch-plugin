package io.fabric8.elasticsearch.plugin.model;

import org.apache.commons.lang.ObjectUtils;

/**
 * @author gallardot
 * @date 6/11/19
 */
public class ServiceAccount implements Comparable<ServiceAccount> {
    private final String name;
    private final String namespace;

    public ServiceAccount(String name, String namespace) {
        this.name = name;
        this.namespace = namespace;
    }


    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((namespace == null) ? 0 : namespace.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ServiceAccount other = (ServiceAccount) obj;
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!name.equals(other.name)) {
            return false;
        }
        if (namespace == null) {
            if (other.namespace != null) {
                return false;
            }
        } else if (!namespace.equals(other.namespace)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ServiceAccount [name=" + ObjectUtils.defaultIfNull(name, "<null>") + ", namespace=" + ObjectUtils.defaultIfNull(namespace,"<null>") + "]";
    }

    @Override
    public int compareTo(ServiceAccount o) {
        if (o.getName() == null) {
            return 1;
        }
        if (this.getName() == null) {
            return -1;
        }
        return this.getName().compareTo(o.getName());
    }
}
