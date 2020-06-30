/**
 * ParameterizedOutput.java
 * 
 * (C) 2020 Dr. Bassler & Co. Managementberatung GmbH
 */
package de.dlr.proseo.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.persistence.ElementCollection;
import javax.persistence.Entity;

/**
 * A set of parameters to apply to a generated product of a processing order
 * 
 * @author Dr. Thomas Bassler
 *
 */
@Entity
public class ParameterizedOutput extends PersistentObject {

	/**
	 * Output parameters consisting of a key (attribute or parameter name) and a value to set on a generated product 
	 */
	@ElementCollection
	private Map<String, Parameter> outputParameters = new HashMap<>();

	/**
	 * @return the outputParameters
	 */
	public Map<String, Parameter> getOutputParameters() {
		return outputParameters;
	}

	/**
	 * @param outputParameters the outputParameters to set
	 */
	public void setOutputParameters(Map<String, Parameter> outputParameters) {
		this.outputParameters = outputParameters;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + Objects.hash(outputParameters);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (!(obj instanceof ParameterizedOutput))
			return false;
		ParameterizedOutput other = (ParameterizedOutput) obj;
		return Objects.equals(outputParameters, other.outputParameters);
	}
}
